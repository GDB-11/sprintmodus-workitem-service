package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.ChangeStatus;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Commands.MoveToSprint;
import com.sprintmodus.workitem_service.application.dto.Commands.SetParent;
import com.sprintmodus.workitem_service.application.dto.Commands.UpdateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.SprintMoveError;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.service.BurndownRecorder;
import com.sprintmodus.workitem_service.application.service.ChangeWorkItemStatusUseCase;
import com.sprintmodus.workitem_service.application.service.CreateWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.DeleteWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.GetWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.MoveToSprintUseCase;
import com.sprintmodus.workitem_service.application.service.SetParentUseCase;
import com.sprintmodus.workitem_service.application.service.SprintVelocitySync;
import com.sprintmodus.workitem_service.application.service.UpdateWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.WorkItemDetails;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.support.Fakes;

class WorkItemUseCasesTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Assignments assignments = new Fakes.Assignments(items);

	private final Fakes.Links links = new Fakes.Links(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Fakes.Projects projects = new Fakes.Projects();

	private final WorkItemDetails details = new WorkItemDetails(items, assignments, links, workflows);

	private final SprintVelocitySync velocity = new SprintVelocitySync(items, projects);

	private final Fakes.Burndown burndownSnapshots = new Fakes.Burndown();

	private final BurndownRecorder burndown = new BurndownRecorder(burndownSnapshots);

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final Actor admin = new Actor(UUID.randomUUID(), OrganizationRole.ADMIN);

	private final CreateWorkItemUseCase create = new CreateWorkItemUseCase(items, workflows, audit, transactions, details);

	private final UpdateWorkItemUseCase update = new UpdateWorkItemUseCase(items, audit, transactions, details, velocity, burndown);

	private final SetParentUseCase setParent = new SetParentUseCase(items, audit, transactions, details, burndown);

	private final DeleteWorkItemUseCase delete = new DeleteWorkItemUseCase(items, audit, transactions, velocity, burndown);

	private final ChangeWorkItemStatusUseCase changeStatus = new ChangeWorkItemStatusUseCase(items, workflows, assignments, audit, transactions, details, velocity, burndown);

	private final MoveToSprintUseCase move = new MoveToSprintUseCase(items, projects, audit, transactions, details, velocity, burndown);

	private UUID project;

	@BeforeEach
	void aProject() {
		project = items.addProject("WAR");
	}

	private CreateWorkItem command(ItemType type, String title) {
		return new CreateWorkItem(member, project, type, title, null, null, null, null, null, null, null);
	}

	private WorkItemResponse created(ItemType type) {
		return create.execute(command(type, "A " + type)).getValue();
	}

	// ------------------------------------------------------------------ create

	@Test
	void everyTypeIsCreatedInItsInitialStatusAndNumberedFrom1000() {
		int expected = 1000;
		for (ItemType type : ItemType.values()) {
			var item = created(type);

			assertThat(item.type()).isEqualTo(type);
			assertThat(item.status().code()).isEqualTo("NEW");
			assertThat(item.number()).isEqualTo(expected);
			assertThat(item.displayKey()).isEqualTo("WAR-" + expected);
			expected++;
		}
	}

	@Test
	void theInitialStatusIsTheOneWithTheLowestOrderOfThatTypesWorkflow() {
		workflows.byType.put(ItemType.EPIC, new Workflow(ItemType.EPIC,
				List.of(new Workflow.WorkflowStatus("READY", "Ready", 5, false, true), new Workflow.WorkflowStatus("BACKLOG", "Backlog", 2, false, true),
						new Workflow.WorkflowStatus("CLOSED", "Closed", 9, true, true)),
				List.of()));

		assertThat(created(ItemType.EPIC).status().code()).isEqualTo("BACKLOG");
	}

	@Test
	void numbersAreIndependentPerProject() {
		UUID other = items.addProject("OTH");

		assertThat(created(ItemType.PBI).number()).isEqualTo(1000);
		assertThat(created(ItemType.PBI).number()).isEqualTo(1001);
		assertThat(create.execute(new CreateWorkItem(member, other, ItemType.PBI, "Other", null, null, null, null, null, null, null)).getValue().displayKey())
				.isEqualTo("OTH-1000");
	}

	@Test
	void storesTheGivenFieldsAndFillsInDefaults() {
		var result = create.execute(new CreateWorkItem(member, project, ItemType.PBI, "  Login page  ", " Users sign in ", " Works on mobile ", Priority.HIGH,
				null, 5, new BigDecimal("8"), null));

		var item = result.getValue();
		assertThat(item.title()).isEqualTo("Login page");
		assertThat(item.description()).isEqualTo("Users sign in");
		assertThat(item.acceptanceCriteria()).isEqualTo("Works on mobile");
		assertThat(item.priority()).isEqualTo(Priority.HIGH);
		assertThat(item.effortPoints()).isEqualTo(5);
		assertThat(item.estimatedHours()).isEqualByComparingTo("8");
		assertThat(item.remainingHours()).as("defaults to the estimate").isEqualByComparingTo("8");
		assertThat(item.createdBy().userCode()).isEqualTo(member.userCode());
		assertThat(item.warnings()).isEmpty();
	}

	@Test
	void defaultsToMediumPriorityAndNoEffort() {
		var item = created(ItemType.TASK);

		assertThat(item.priority()).isEqualTo(Priority.MEDIUM);
		assertThat(item.effortPoints()).isZero();
		assertThat(item.estimatedHours()).isEqualByComparingTo("0");
		assertThat(item.description()).isNull();
	}

	@Test
	void recordsCreatedInTheSameTransactionAsTheItem() {
		var item = created(ItemType.PBI);

		assertThat(audit.types()).containsExactly("CREATED");
		var entry = audit.entries.getFirst();
		assertThat(entry.workItemCode()).isEqualTo(item.code());
		assertThat(entry.changedBy()).isEqualTo(member.userCode());
		assertThat(entry.additionalData()).containsEntry("displayKey", "WAR-1000").containsEntry("status", "NEW");
		assertThat(transactions.runs).isEqualTo(1);
		assertThat(transactions.auditSizeAtEnd).as("the entry was written before the transaction ended").containsExactly(1);
	}

	@Test
	void validatesTheInputAndCreatesNothing() {
		assertThat(create.execute(new CreateWorkItem(member, null, ItemType.PBI, "T", null, null, null, null, null, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidWorkItemData.class, error -> assertThat(error.field()).isEqualTo("projectCode"));
		assertThat(create.execute(new CreateWorkItem(member, project, null, "T", null, null, null, null, null, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidWorkItemData.class, error -> assertThat(error.field()).isEqualTo("type"));
		for (String title : new String[] { null, "", "   " }) {
			assertThat(create.execute(command(ItemType.PBI, title)).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		}
		assertThat(create.execute(command(ItemType.PBI, "x".repeat(501))).isFailure()).isTrue();
		assertThat(create.execute(command(ItemType.PBI, "x".repeat(500))).isSuccess()).isTrue();
		assertThat(create.execute(new CreateWorkItem(member, project, ItemType.PBI, "T", "d".repeat(50_001), null, null, null, null, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidWorkItemData.class, error -> assertThat(error.field()).isEqualTo("description"));
		assertThat(create.execute(new CreateWorkItem(member, project, ItemType.PBI, "T", null, null, null, null, 1001, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidWorkItemData.class, error -> assertThat(error.field()).isEqualTo("effortPoints"));
		assertThat(items.stored).hasSize(1);
		assertThat(audit.entries).hasSize(1);
	}

	@Test
	void anUnknownProjectIsNotFoundAndLeavesNoAuditEntry() {
		var result = create.execute(new CreateWorkItem(member, UUID.randomUUID(), ItemType.PBI, "T", null, null, null, null, null, null, null));

		assertThat(result.getError()).isInstanceOf(WorkItemError.ProjectNotFound.class);
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void aTypeWithoutAnActiveStatusCannotBeCreated() {
		workflows.byType.put(ItemType.BUG, new Workflow(ItemType.BUG, List.of(), List.of()));

		assertThat(create.execute(command(ItemType.BUG, "Crash")).getError()).isInstanceOf(WorkItemError.WorkflowNotConfigured.class);
		assertThat(items.stored).isEmpty();
	}

	// ------------------------------------------------------------------ hierarchy warnings never block

	@Test
	void aRecommendedParentGivesNoWarning() {
		var epic = created(ItemType.EPIC);

		var feature = create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "F", null, null, null, epic.code(), null, null, null)).getValue();

		assertThat(feature.parentCode()).isEqualTo(epic.code());
		assertThat(feature.warnings()).isEmpty();
	}

	@Test
	void aNonStandardParentSucceedsWithAWarning() {
		var pbi = created(ItemType.PBI);

		// "Feature under PBI" is one of the architecture doc's examples of a permitted, non-recommended parent
		var feature = create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "F", null, null, null, pbi.code(), null, null, null));

		assertThat(feature.isSuccess()).isTrue();
		assertThat(feature.getValue().parentCode()).isEqualTo(pbi.code());
		assertThat(feature.getValue().warnings()).singleElement().satisfies(warning -> {
			assertThat(warning.code()).isEqualTo(Warning.NON_STANDARD_HIERARCHY);
			assertThat(warning.message()).contains("PBI is not a recommended parent for FEATURE");
		});
	}

	@Test
	void aTaskUnderATaskAndAnEpicUnderAFeatureAlsoWarnButSucceed() {
		var task = created(ItemType.TASK);
		var feature = created(ItemType.FEATURE);

		var subTask = create.execute(new CreateWorkItem(member, project, ItemType.TASK, "T2", null, null, null, task.code(), null, null, null)).getValue();
		var epic = create.execute(new CreateWorkItem(member, project, ItemType.EPIC, "E", null, null, null, feature.code(), null, null, null)).getValue();

		assertThat(subTask.warnings()).hasSize(1);
		assertThat(epic.warnings()).hasSize(1);
	}

	@Test
	void theParentMustExistAndBeInTheSameProject() {
		UUID otherProject = items.addProject("OTH");
		var foreign = create.execute(new CreateWorkItem(member, otherProject, ItemType.EPIC, "E", null, null, null, null, null, null, null)).getValue();

		assertThat(create.execute(new CreateWorkItem(member, project, ItemType.PBI, "P", null, null, null, UUID.randomUUID(), null, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidParent.class, error -> assertThat(error.message()).contains("not found"));
		assertThat(create.execute(new CreateWorkItem(member, project, ItemType.PBI, "P", null, null, null, foreign.code(), null, null, null)).getError())
				.isInstanceOfSatisfying(WorkItemError.InvalidParent.class, error -> assertThat(error.message()).contains("same project"));
	}

	// ------------------------------------------------------------------ update

	@Test
	void anEditWritesOneAuditEntryPerChangedFieldInOneTransaction() {
		var item = created(ItemType.PBI);
		audit.entries.clear();
		transactions.runs = 0;

		var result = update.execute(new UpdateWorkItem(member, item.code(), "Renamed", "Now with text", null, Priority.CRITICAL, 8, new BigDecimal("6"), null));

		var updated = result.getValue();
		assertThat(updated.title()).isEqualTo("Renamed");
		assertThat(updated.priority()).isEqualTo(Priority.CRITICAL);
		assertThat(updated.effortPoints()).isEqualTo(8);
		assertThat(updated.updatedBy().userCode()).isEqualTo(member.userCode());
		assertThat(audit.entries).extracting(entry -> entry.type() + ":" + entry.field()).containsExactly("FIELD_CHANGED:Title",
				"DESCRIPTION_EDITED:Description", "FIELD_CHANGED:Priority", "EFFORT_CHANGED:EffortPoints", "EFFORT_CHANGED:EstimatedHours");
		assertThat(transactions.runs).isEqualTo(1);
	}

	@Test
	void fieldsLeftOutKeepTheirValueAndBlankTextClearsIt() {
		var item = create.execute(new CreateWorkItem(member, project, ItemType.PBI, "T", "Some text", "Criteria", null, null, 3, null, null)).getValue();

		var updated = update.execute(new UpdateWorkItem(member, item.code(), null, "   ", null, null, null, null, null)).getValue();

		assertThat(updated.title()).isEqualTo("T");
		assertThat(updated.description()).as("blank clears").isNull();
		assertThat(updated.acceptanceCriteria()).as("left out keeps").isEqualTo("Criteria");
		assertThat(updated.effortPoints()).isEqualTo(3);
	}

	@Test
	void anEditThatChangesNothingWritesNothing() {
		var item = created(ItemType.PBI);
		audit.entries.clear();
		transactions.runs = 0;

		var result = update.execute(new UpdateWorkItem(member, item.code(), "A PBI", null, null, Priority.MEDIUM, 0, BigDecimal.ZERO, BigDecimal.ZERO));

		assertThat(result.isSuccess()).isTrue();
		assertThat(audit.entries).isEmpty();
		assertThat(transactions.runs).isZero();
	}

	@Test
	void updateValidatesAndReportsAMissingItem() {
		var item = created(ItemType.PBI);

		assertThat(update.execute(new UpdateWorkItem(member, item.code(), "  ", null, null, null, null, null, null)).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(update.execute(new UpdateWorkItem(member, item.code(), null, null, null, null, -1, null, null)).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(update.execute(new UpdateWorkItem(member, item.code(), null, null, null, null, null, new BigDecimal("-1"), null)).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(update.execute(new UpdateWorkItem(member, UUID.randomUUID(), "T", null, null, null, null, null, null)).getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void changingTheEffortPointsOfAnItemInASprintRefreshesTheSprintsVelocity() {
		var sprint = projects.add(project, "S1", "ACTIVE").sprintCode();
		var done = items.add(project, ItemType.PBI, "DONE", 5, sprint, member.userCode());

		update.execute(new UpdateWorkItem(member, done.code(), null, null, null, null, 8, null, null));

		assertThat(projects.velocities).containsEntry(sprint, 8);
	}

	@Test
	void otherEditsDoNotTouchProjectService() {
		var sprint = projects.add(project, "S1", "ACTIVE").sprintCode();
		var item = items.add(project, ItemType.PBI, "NEW", 5, sprint, member.userCode());

		update.execute(new UpdateWorkItem(member, item.code(), "Renamed", null, null, null, null, null, null));

		assertThat(projects.velocityCalls).isEmpty();
	}

	// ------------------------------------------------------------------ parent

	@Test
	void setsAndClearsAParentAndRecordsTheChange() {
		var epic = created(ItemType.EPIC);
		var feature = created(ItemType.FEATURE);
		audit.entries.clear();

		var withParent = setParent.execute(new SetParent(member, feature.code(), epic.code())).getValue();
		assertThat(withParent.parentCode()).isEqualTo(epic.code());
		assertThat(withParent.warnings()).isEmpty();
		var cleared = setParent.execute(new SetParent(member, feature.code(), null)).getValue();
		assertThat(cleared.parentCode()).isNull();

		assertThat(audit.entries).extracting(entry -> entry.type() + ":" + entry.oldValue() + ">" + entry.newValue()).containsExactly(
				"PARENT_CHANGED:null>" + epic.code(), "PARENT_CHANGED:" + epic.code() + ">null");
	}

	@Test
	void settingTheSameParentAgainIsANoOp() {
		var epic = created(ItemType.EPIC);
		var feature = create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "F", null, null, null, epic.code(), null, null, null)).getValue();
		audit.entries.clear();

		assertThat(setParent.execute(new SetParent(member, feature.code(), epic.code())).isSuccess()).isTrue();
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void aNonStandardNewParentWarnsButIsApplied() {
		var task = created(ItemType.TASK);
		var pbi = created(ItemType.PBI);

		var result = setParent.execute(new SetParent(member, pbi.code(), task.code())).getValue();

		assertThat(result.parentCode()).isEqualTo(task.code());
		assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning.message()).contains("TASK is not a recommended parent for PBI"));
	}

	@Test
	void aWorkItemCannotBecomeItsOwnAncestor() {
		var epic = created(ItemType.EPIC);
		var feature = create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "F", null, null, null, epic.code(), null, null, null)).getValue();
		var pbi = create.execute(new CreateWorkItem(member, project, ItemType.PBI, "P", null, null, null, feature.code(), null, null, null)).getValue();
		audit.entries.clear();

		// itself, and a grandchild (epic > feature > pbi)
		for (UUID badParent : new UUID[] { epic.code(), pbi.code() }) {
			var result = setParent.execute(new SetParent(member, epic.code(), badParent));
			assertThat(result.getError()).isInstanceOfSatisfying(WorkItemError.InvalidParent.class, error -> assertThat(error.message()).contains("own ancestor"));
		}
		assertThat(setParent.execute(new SetParent(member, epic.code(), feature.code())).getError()).as("its own child").isInstanceOf(WorkItemError.InvalidParent.class);
		assertThat(items.stored.get(epic.code()).parentCode()).isNull();
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void theNewParentMustBeInTheSameProjectAndExist() {
		UUID otherProject = items.addProject("OTH");
		var foreign = create.execute(new CreateWorkItem(member, otherProject, ItemType.EPIC, "E", null, null, null, null, null, null, null)).getValue();
		var pbi = created(ItemType.PBI);

		assertThat(setParent.execute(new SetParent(member, pbi.code(), foreign.code())).getError()).isInstanceOf(WorkItemError.InvalidParent.class);
		assertThat(setParent.execute(new SetParent(member, pbi.code(), UUID.randomUUID())).getError()).isInstanceOf(WorkItemError.InvalidParent.class);
		assertThat(setParent.execute(new SetParent(member, UUID.randomUUID(), null)).getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	// ------------------------------------------------------------------ delete

	@Test
	void theCreatorAndAdminsMayDeleteButOtherMembersMayNot() {
		var item = created(ItemType.PBI);
		Actor stranger = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

		assertThat(delete.execute(stranger, item.code()).getError()).isInstanceOf(WorkItemError.NotAllowed.class);
		assertThat(items.stored).hasSize(1);
		assertThat(delete.execute(member, item.code()).isSuccess()).as("its creator").isTrue();

		var second = created(ItemType.PBI);
		assertThat(delete.execute(admin, second.code()).isSuccess()).as("an admin").isTrue();
		assertThat(items.stored).isEmpty();
	}

	@Test
	void deleteRecordsTheItemAndRefusesWhileItHasChildren() {
		var epic = created(ItemType.EPIC);
		var feature = create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "F", null, null, null, epic.code(), null, null, null)).getValue();
		audit.entries.clear();

		assertThat(delete.execute(member, epic.code()).getError()).isInstanceOf(WorkItemError.HasChildren.class);
		assertThat(audit.entries).isEmpty();

		assertThat(delete.execute(member, feature.code()).isSuccess()).isTrue();
		assertThat(delete.execute(member, epic.code()).isSuccess()).isTrue();
		assertThat(audit.entries).extracting(entry -> entry.type() + ":" + entry.oldValue()).containsExactly("DELETED:F", "DELETED:A EPIC");
		assertThat(delete.execute(member, epic.code()).getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void deletingACompletedItemInASprintLowersTheSprintsVelocity() {
		var sprint = projects.add(project, "S1", "ACTIVE").sprintCode();
		var done = items.add(project, ItemType.PBI, "DONE", 5, sprint, member.userCode());
		items.add(project, ItemType.PBI, "DONE", 3, sprint, member.userCode());

		delete.execute(member, done.code());

		assertThat(projects.velocities).containsEntry(sprint, 3);
	}

	// ------------------------------------------------------------------ status

	@Test
	void movesAlongTheWorkflowAndRecordsTheChangeInTheSameTransaction() {
		var item = created(ItemType.PBI);
		audit.entries.clear();
		transactions.runs = 0;
		transactions.auditSizeAtEnd.clear();

		var moved = changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE")).getValue();

		assertThat(moved.status().code()).isEqualTo("ACTIVE");
		assertThat(moved.allowedStatuses()).extracting(status -> status.code()).containsExactly("NEW", "DONE");
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.STATE_CHANGED);
			assertThat(entry.field()).isEqualTo("Status");
			assertThat(entry.oldValue()).isEqualTo("NEW");
			assertThat(entry.newValue()).isEqualTo("ACTIVE");
			assertThat(entry.changedBy()).isEqualTo(member.userCode());
			assertThat(entry.additionalData()).containsEntry("from", "New").containsEntry("to", "Active");
		});
		assertThat(transactions.runs).isEqualTo(1);
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
	}

	@Test
	void backwardMovesWorkOnlyWhereTheWorkflowAllowsThem() {
		var item = created(ItemType.PBI);
		changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE"));

		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "NEW")).getValue().status().code()).as("NEW-ACTIVE is reversible").isEqualTo("NEW");

		changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE"));
		changeStatus.execute(new ChangeStatus(member, item.code(), "DONE"));
		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE")).getError()).as("ACTIVE-DONE is not").isInstanceOf(StatusTransitionError.InvalidTransition.class);
	}

	@Test
	void aTransitionOutsideTheWorkflowIsRejectedWithAClearMessageAndNothingIsWritten() {
		var item = created(ItemType.PBI);
		audit.entries.clear();

		var result = changeStatus.execute(new ChangeStatus(member, item.code(), "DONE"));

		assertThat(result.getError()).isInstanceOf(StatusTransitionError.InvalidTransition.class);
		assertThat(result.getError().message()).isEqualTo("Cannot move from New to Done.");
		assertThat(audit.entries).isEmpty();
		assertThat(items.stored.get(item.code()).status().code()).isEqualTo("NEW");
	}

	@Test
	void staticInputIsNormalizedAndUnknownStatusesAreNamed() {
		var item = created(ItemType.PBI);

		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), " active ")).isSuccess()).isTrue();
		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "FLYING")).getError()).isEqualTo(new StatusTransitionError.UnknownStatus("FLYING"));
		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), null)).getError()).isInstanceOf(StatusTransitionError.UnknownStatus.class);
		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE")).getError()).as("already there").isInstanceOf(StatusTransitionError.InvalidTransition.class);
		assertThat(changeStatus.execute(new ChangeStatus(member, UUID.randomUUID(), "ACTIVE")).getError()).isInstanceOf(StatusTransitionError.WorkItemNotFound.class);
	}

	@Test
	void aRetiredStatusIsNotATarget() {
		var item = created(ItemType.PBI);
		var wf = workflows.find(ItemType.PBI);
		workflows.replace(new Workflow(ItemType.PBI, List.of(new Workflow.WorkflowStatus("NEW", "New", 1, false, true),
				new Workflow.WorkflowStatus("ACTIVE", "Active", 2, false, false), new Workflow.WorkflowStatus("DONE", "Done", 3, true, true)),
				wf.transitions()));

		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE")).getError()).isInstanceOf(StatusTransitionError.UnknownStatus.class);
	}

	@Test
	void whenSomeoneElseMovedTheItemFirstTheChangeIsRefusedAndNoAuditEntryIsWritten() {
		var item = created(ItemType.PBI);
		audit.entries.clear();
		items.statusRace = true;

		var result = changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE"));

		assertThat(result.getError()).isInstanceOf(StatusTransitionError.StatusChangedConcurrently.class);
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void aTransitionCanRequireAnAssignmentRoleWhichAnAdminMayOverride() {
		var item = created(ItemType.PBI);
		changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE"));
		var wf = workflows.find(ItemType.PBI);
		workflows.replace(new Workflow(ItemType.PBI, wf.statuses(),
				List.of(new Workflow.WorkflowTransition("NEW", "ACTIVE", true),
						new Workflow.WorkflowTransition("ACTIVE", "DONE", false, AssignmentRole.PO))));
		audit.entries.clear();

		var refused = changeStatus.execute(new ChangeStatus(member, item.code(), "DONE"));

		assertThat(refused.getError()).isEqualTo(new StatusTransitionError.NotAllowed(AssignmentRole.PO));
		assertThat(items.stored.get(item.code()).status().code()).as("nothing changed").isEqualTo("ACTIVE");
		assertThat(audit.entries).isEmpty();
		assertThat(changeStatus.execute(new ChangeStatus(admin, item.code(), "DONE")).isSuccess())
				.as("an org admin may override the restriction").isTrue();
	}

	@Test
	void anAssigneeWithTheRequiredRoleMayMakeARestrictedMove() {
		var item = created(ItemType.PBI);
		changeStatus.execute(new ChangeStatus(member, item.code(), "ACTIVE"));
		var wf = workflows.find(ItemType.PBI);
		workflows.replace(new Workflow(ItemType.PBI, wf.statuses(),
				List.of(new Workflow.WorkflowTransition("NEW", "ACTIVE", true),
						new Workflow.WorkflowTransition("ACTIVE", "DONE", false, AssignmentRole.PO))));
		UUID ana = assignments.addUser("Ana Diaz");
		assignments.assign(item.code(), ana, AssignmentRole.PO);
		Actor po = new Actor(ana, OrganizationRole.MEMBER);
		assertThat(changeStatus.execute(new ChangeStatus(member, item.code(), "DONE")).getError())
				.as("assigned in the wrong role").isEqualTo(new StatusTransitionError.NotAllowed(AssignmentRole.PO));

		assertThat(changeStatus.execute(new ChangeStatus(po, item.code(), "DONE")).isSuccess()).isTrue();
	}

	@Test
	void reachingOrLeavingATerminalStatusRefreshesTheSprintsVelocityButOtherMovesDoNot() {
		var sprint = projects.add(project, "S1", "ACTIVE").sprintCode();
		var pbi = items.add(project, ItemType.PBI, "NEW", 5, sprint, member.userCode());

		changeStatus.execute(new ChangeStatus(member, pbi.code(), "ACTIVE"));
		assertThat(projects.velocityCalls).as("NEW to ACTIVE completes nothing").isEmpty();

		changeStatus.execute(new ChangeStatus(member, pbi.code(), "DONE"));
		assertThat(projects.velocities).containsEntry(sprint, 5);
	}

	@Test
	void anItemInTheBacklogNeverTouchesProjectService() {
		var pbi = items.add(project, ItemType.PBI, "ACTIVE", 5, null, member.userCode());

		changeStatus.execute(new ChangeStatus(member, pbi.code(), "DONE"));

		assertThat(projects.velocityCalls).isEmpty();
	}

	// ------------------------------------------------------------------ move to sprint

	@Test
	void movingBetweenSprintsUpdatesTheVelocityOfBothEnds() {
		var from = projects.add(project, "Sprint 1", "ACTIVE").sprintCode();
		var to = projects.add(project, "Sprint 2", "PLANNED").sprintCode();
		var moving = items.add(project, ItemType.PBI, "DONE", 5, from, admin.userCode());
		items.add(project, ItemType.PBI, "DONE", 3, from, admin.userCode());
		items.add(project, ItemType.BUG, "DONE", 2, to, admin.userCode());

		var result = move.execute(new MoveToSprint(admin, moving.code(), to));

		assertThat(result.getValue().sprintCode()).isEqualTo(to);
		assertThat(result.getValue().warnings()).isEmpty();
		assertThat(projects.velocities).as("the item's 5 points left one sprint and joined the other").containsEntry(from, 3).containsEntry(to, 7);
	}

	@Test
	void onlyOwnersAndAdminsPlanWorkIntoSprints() {
		var to = projects.add(project, "Sprint 2", "PLANNED").sprintCode();
		var item = items.add(project, ItemType.PBI, "NEW", 5, null, member.userCode());

		var refused = move.execute(new MoveToSprint(member, item.code(), to));
		var backlog = move.execute(new MoveToSprint(member, item.code(), null));

		assertThat(refused.getError()).isInstanceOf(SprintMoveError.NotAllowed.class);
		assertThat(backlog.getError()).isInstanceOf(SprintMoveError.NotAllowed.class);
		assertThat(items.stored.get(item.code()).sprintCode()).isNull();
		assertThat(audit.entries).isEmpty();
		assertThat(move.execute(new MoveToSprint(new Actor(UUID.randomUUID(), OrganizationRole.OWNER), item.code(), to)).isSuccess()).isTrue();
	}

	@Test
	void theMoveIsAuditedInTheSameTransactionWithTheSprintName() {
		var to = projects.add(project, "Sprint 2", "PLANNED").sprintCode();
		var item = items.add(project, ItemType.PBI, "NEW", 5, null, admin.userCode());

		move.execute(new MoveToSprint(admin, item.code(), to));

		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.SPRINT_CHANGED);
			assertThat(entry.oldValue()).isNull();
			assertThat(entry.newValue()).isEqualTo(to.toString());
			assertThat(entry.additionalData()).containsEntry("sprint", "Sprint 2");
			assertThat(entry.changedBy()).isEqualTo(admin.userCode());
		});
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
	}

	@Test
	void movingBackToTheBacklogRefreshesOnlyTheSprintItLeft() {
		var from = projects.add(project, "Sprint 1", "ACTIVE").sprintCode();
		var item = items.add(project, ItemType.PBI, "DONE", 5, from, admin.userCode());

		var result = move.execute(new MoveToSprint(admin, item.code(), null));

		assertThat(result.getValue().sprintCode()).isNull();
		assertThat(projects.velocities).containsEntry(from, 0);
		assertThat(projects.velocityCalls).containsExactly(from);
		assertThat(audit.entries.getFirst().newValue()).isNull();
	}

	@Test
	void theSameSprintAgainIsANoOp() {
		var sprint = projects.add(project, "S", "ACTIVE").sprintCode();
		var item = items.add(project, ItemType.PBI, "NEW", 1, sprint, admin.userCode());

		assertThat(move.execute(new MoveToSprint(admin, item.code(), sprint)).isSuccess()).isTrue();
		assertThat(audit.entries).isEmpty();
		assertThat(projects.velocityCalls).isEmpty();
	}

	@Test
	void theTargetSprintMustExistBelongToTheProjectAndNotBeClosed() {
		var item = items.add(project, ItemType.PBI, "NEW", 1, null, admin.userCode());
		var closed = projects.add(project, "Old", "CLOSED").sprintCode();
		var foreign = projects.add(UUID.randomUUID(), "Other project", "PLANNED").sprintCode();

		assertThat(move.execute(new MoveToSprint(admin, item.code(), UUID.randomUUID())).getError()).isInstanceOf(SprintMoveError.SprintNotFound.class);
		assertThat(move.execute(new MoveToSprint(admin, item.code(), closed)).getError()).isInstanceOf(SprintMoveError.SprintClosed.class);
		assertThat(move.execute(new MoveToSprint(admin, item.code(), foreign)).getError()).isInstanceOf(SprintMoveError.SprintOfAnotherProject.class);
		assertThat(move.execute(new MoveToSprint(admin, UUID.randomUUID(), closed)).getError()).isInstanceOf(SprintMoveError.WorkItemNotFound.class);
		assertThat(items.stored.get(item.code()).sprintCode()).isNull();
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void whenProjectServiceIsDownNothingChanges() {
		var sprint = projects.add(project, "S", "ACTIVE").sprintCode();
		var item = items.add(project, ItemType.PBI, "NEW", 1, null, admin.userCode());
		projects.down = true;

		var result = move.execute(new MoveToSprint(admin, item.code(), sprint));

		assertThat(result.getError()).isInstanceOf(SprintMoveError.ProjectServiceUnavailable.class);
		assertThat(items.stored.get(item.code()).sprintCode()).isNull();
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void aFailedVelocityUpdateDoesNotUndoTheMoveButWarns() {
		var sprint = projects.add(project, "S", "ACTIVE").sprintCode();
		var item = items.add(project, ItemType.PBI, "DONE", 5, null, admin.userCode());
		projects.velocityFails = true;

		var result = move.execute(new MoveToSprint(admin, item.code(), sprint));

		assertThat(result.isSuccess()).isTrue();
		assertThat(result.getValue().sprintCode()).isEqualTo(sprint);
		assertThat(result.getValue().warnings()).singleElement().satisfies(warning -> assertThat(warning.code()).isEqualTo(Warning.VELOCITY_NOT_UPDATED));
		assertThat(audit.types()).containsExactly("SPRINT_CHANGED");
	}

	// ------------------------------------------------------------------ read

	@Test
	void theDetailListsChildrenAndWhereTheItemCanMoveNext() {
		var epic = created(ItemType.EPIC);
		create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "Child", null, null, null, epic.code(), null, null, null));

		var detail = new GetWorkItemUseCase(details).execute(epic.code()).getValue();

		assertThat(detail.children()).singleElement().satisfies(child -> {
			assertThat(child.displayKey()).isEqualTo("WAR-1001");
			assertThat(child.type()).isEqualTo(ItemType.FEATURE);
		});
		assertThat(detail.allowedStatuses()).extracting(status -> status.code()).containsExactly("ACTIVE");
		assertThat(new GetWorkItemUseCase(details).execute(UUID.randomUUID()).getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void childrenEffortPointsSumsOnlyActiveTaskChildrenAndNeverReplacesTheItemsOwnEstimate() {
		var pbi = created(ItemType.PBI);
		update.execute(new UpdateWorkItem(member, pbi.code(), null, null, null, null, 5, null, null));
		create.execute(new CreateWorkItem(member, project, ItemType.TASK, "T1", null, null, null, pbi.code(), 3, null, null));
		var t2 = create.execute(new CreateWorkItem(member, project, ItemType.TASK, "T2", null, null, null, pbi.code(), 2, null, null)).getValue();
		create.execute(new CreateWorkItem(member, project, ItemType.FEATURE, "Not a task", null, null, null, pbi.code(), null, null, null));

		var detail = new GetWorkItemUseCase(details).execute(pbi.code()).getValue();
		assertThat(detail.effortPoints()).as("the PBI's own estimate is untouched").isEqualTo(5);
		assertThat(detail.childrenEffortPoints()).as("3 + 2 from the two Task children only").isEqualTo(5);

		delete.execute(member, t2.code());
		var afterDelete = new GetWorkItemUseCase(details).execute(pbi.code()).getValue();
		assertThat(afterDelete.childrenEffortPoints()).as("a deleted task no longer counts").isEqualTo(3);
	}

}
