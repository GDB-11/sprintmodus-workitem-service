package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway.ProjectInfo;
import com.sprintmodus.workitem_service.application.service.BurndownRecorder;
import com.sprintmodus.workitem_service.application.service.ImportWorkItemsUseCase;
import com.sprintmodus.workitem_service.application.service.ImportWorkItemsUseCase.Command;
import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic.Code;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.support.Fakes;

class ImportWorkItemsUseCaseTest {

	private static final String EPICS = """
			# Épica: Incorporación
			- prioridad: alta

			Objetivo.

			## Característica: Verificación
			**Criterios de aceptación**
			- [ ] Caduca a las 24 h

			### Elemento del backlog: Recibir correo
			- puntos: 5
			- sprint: Sprint 3
			- asignado: ana@acme.com

			#### Tarea: Plantilla
			- horas: 3
			- asignado: Nadie Conocido
			""";

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Assignments assignments = new Fakes.Assignments(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Fakes.Projects projects = new Fakes.Projects();

	private final Fakes.Users users = new Fakes.Users();

	private final Fakes.Burndown snapshots = new Fakes.Burndown();

	private final ImportWorkItemsUseCase importer = new ImportWorkItemsUseCase(items, workflows, audit, assignments, users, projects, transactions,
			new BurndownRecorder(snapshots));

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final Actor admin = new Actor(UUID.randomUUID(), OrganizationRole.ADMIN);

	private UUID project;

	private UUID sprint;

	private UUID ana;

	@BeforeEach
	void aProjectWithASprintAndAPerson() {
		project = items.addProject("WAR");
		projects.projects.put(project, new ProjectInfo(project, "War"));
		sprint = projects.add(project, "Sprint 3", "PLANNED").sprintCode();
		ana = assignments.addUser("Ana Díaz");
		users.add(ana, "Ana Díaz", "ana@acme.com");
	}

	private Command command(Actor actor, UUID parent, String markdown, boolean dryRun) {
		return new Command(actor, project, parent, markdown, dryRun);
	}

	@Test
	void aDryRunWritesNothingAndReportsTheTree() {
		var outcome = importer.execute(command(admin, null, EPICS, true)).getValue();

		assertThat(outcome.imported()).isFalse();
		assertThat(outcome.items()).hasSize(1);
		assertThat(items.stored).isEmpty();
		assertThat(audit.entries).isEmpty();
		assertThat(outcome.diagnostics()).extracting(d -> d.code()).containsExactly(Code.UNKNOWN_ASSIGNEE);
	}

	@Test
	void createsTheWholeTreeInTheInitialStatusWithTheirParentsAndAudit() {
		var outcome = importer.execute(command(admin, null, EPICS, false)).getValue();

		assertThat(outcome.imported()).isTrue();
		assertThat(outcome.created()).extracting(c -> c.displayKey()).containsExactly("WAR-1000");
		assertThat(items.stored.values()).extracting(WorkItem::type).containsExactlyInAnyOrder(ItemType.EPIC, ItemType.FEATURE, ItemType.PBI, ItemType.TASK);
		assertThat(items.stored.values()).allSatisfy(item -> assertThat(item.status().code()).isEqualTo("NEW"));
		WorkItem epic = byType(ItemType.EPIC);
		WorkItem feature = byType(ItemType.FEATURE);
		WorkItem pbi = byType(ItemType.PBI);
		WorkItem task = byType(ItemType.TASK);
		assertThat(epic.priority()).isEqualTo(Priority.HIGH);
		assertThat(epic.parentCode()).isNull();
		assertThat(feature.parentCode()).isEqualTo(epic.code());
		assertThat(feature.acceptanceCriteria()).isEqualTo("Caduca a las 24 h");
		assertThat(pbi.parentCode()).isEqualTo(feature.code());
		assertThat(pbi.effortPoints()).isEqualTo(5);
		assertThat(pbi.sprintCode()).isEqualTo(sprint);
		assertThat(task.estimatedHours()).isEqualByComparingTo("3");
		assertThat(task.remainingHours()).isEqualByComparingTo("3");
		assertThat(audit.entries.stream().filter(e -> e.type() == ChangeType.CREATED)).hasSize(4);
		assertThat(assignments.findByWorkItem(pbi.code())).extracting(a -> a.userCode()).containsExactly(ana);
		assertThat(assignments.findByWorkItem(task.code())).isEmpty();
		assertThat(snapshots.snapshots).containsExactly(sprint);
	}

	@Test
	void aMemberCreatesTheItemsButCannotPlanSprints() {
		var outcome = importer.execute(command(member, null, EPICS, false)).getValue();

		assertThat(outcome.imported()).isTrue();
		assertThat(byType(ItemType.PBI).sprintCode()).isNull();
		assertThat(outcome.diagnostics()).extracting(d -> d.code()).contains(Code.UNKNOWN_SPRINT);
	}

	@Test
	void aClosedSprintIsNotUsed() {
		projects.sprints.clear();
		projects.add(project, "Sprint 3", "CLOSED");

		var outcome = importer.execute(command(admin, null, EPICS, false)).getValue();

		assertThat(byType(ItemType.PBI).sprintCode()).isNull();
		assertThat(outcome.diagnostics()).extracting(d -> d.code()).contains(Code.UNKNOWN_SPRINT);
	}

	@Test
	void anErrorAnywhereCreatesNothing() {
		var outcome = importer.execute(command(admin, null, "# Épica: Una\n\n# Característica: Mal puesta\n", false)).getValue();

		assertThat(outcome.imported()).isFalse();
		assertThat(outcome.hasErrors()).isTrue();
		assertThat(items.stored).isEmpty();
	}

	@Test
	void theScreensParentDecidesTheTypeOfTheRoots() {
		WorkItem epic = items.add(project, ItemType.EPIC, "NEW", 0, null, member.userCode());

		var features = importer.execute(command(member, epic.code(), "# Característica: Una\n# Característica: Otra\n", false)).getValue();
		assertThat(features.imported()).isTrue();
		assertThat(items.stored.values().stream().filter(i -> epic.code().equals(i.parentCode()))).hasSize(2);

		var wrong = importer.execute(command(member, epic.code(), "# Elemento del backlog: No aquí\n", false)).getValue();
		assertThat(wrong.imported()).isFalse();
		assertThat(wrong.diagnostics()).extracting(d -> d.code()).contains(Code.ROOT_TYPE_MISMATCH);
	}

	@Test
	void refusesAParentThatCannotHaveImportsAnOtherProjectsParentOrAMissingOne() {
		WorkItem task = items.add(project, ItemType.TASK, "NEW", 0, null, member.userCode());
		UUID other = items.addProject("OTH");
		WorkItem foreign = items.add(other, ItemType.EPIC, "NEW", 0, null, member.userCode());

		assertThat(importer.execute(command(member, task.code(), "- algo\n", false)).getError()).isInstanceOf(WorkItemError.InvalidParent.class);
		assertThat(importer.execute(command(member, foreign.code(), "# Característica: X\n", false)).getError())
				.isInstanceOf(WorkItemError.InvalidParent.class);
		assertThat(importer.execute(command(member, UUID.randomUUID(), "# Característica: X\n", false)).getError())
				.isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void anUnknownProjectIsNotFound() {
		var result = importer.execute(new Command(member, UUID.randomUUID(), null, "# Épica: X\n", false));

		assertThat(result.getError()).isInstanceOf(WorkItemError.ProjectNotFound.class);
	}

	@Test
	void anEmptyFileIsInvalid() {
		assertThat(importer.execute(command(member, null, "  \n", false)).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
	}

	@Test
	void anAmbiguousNameIsNotGuessed() {
		users.add(UUID.randomUUID(), "Luis Pérez", "luis1@acme.com");
		users.add(UUID.randomUUID(), "Luis Pérez", "luis2@acme.com");

		var outcome = importer.execute(command(admin, null, "# Épica: E\n## Característica: F\n### Elemento del backlog: P\n- asignado: Luis Pérez\n", false)).getValue();

		assertThat(outcome.diagnostics()).extracting(d -> d.code()).contains(Code.UNKNOWN_ASSIGNEE);
		assertThat(assignments.stored).isEmpty();
	}

	private WorkItem byType(ItemType type) {
		return items.stored.values().stream().filter(item -> item.type() == type).findFirst().orElseThrow();
	}

}
