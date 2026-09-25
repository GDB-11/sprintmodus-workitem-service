package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.ChangeStatus;
import com.sprintmodus.workitem_service.application.dto.Commands.MoveToSprint;
import com.sprintmodus.workitem_service.application.dto.Commands.SetParent;
import com.sprintmodus.workitem_service.application.dto.Commands.UpdateWorkItem;
import com.sprintmodus.workitem_service.application.service.BurndownRecorder;
import com.sprintmodus.workitem_service.application.service.ChangeWorkItemStatusUseCase;
import com.sprintmodus.workitem_service.application.service.DeleteWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.MoveToSprintUseCase;
import com.sprintmodus.workitem_service.application.service.SetParentUseCase;
import com.sprintmodus.workitem_service.application.service.SprintVelocitySync;
import com.sprintmodus.workitem_service.application.service.UpdateWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.WorkItemDetails;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.support.Fakes;

/**
 * A sprint's burndown is recorded after every change that alters the hours left in it: edited hours, an item finished or
 * reopened, moved in or out, deleted, given another parent. It never fails the change it follows.
 */
class BurndownRecordingTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Assignments assignments = new Fakes.Assignments(items);

	private final Fakes.Links links = new Fakes.Links(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Fakes.Projects projects = new Fakes.Projects();

	private final WorkItemDetails details = new WorkItemDetails(items, assignments, links, workflows);

	private final SprintVelocitySync velocity = new SprintVelocitySync(items, projects);

	private final Fakes.Burndown snapshots = new Fakes.Burndown();

	private final BurndownRecorder burndown = new BurndownRecorder(snapshots);

	private final Actor admin = new Actor(UUID.randomUUID(), OrganizationRole.ADMIN);

	private final UpdateWorkItemUseCase update = new UpdateWorkItemUseCase(items, audit, transactions, details, velocity, burndown);

	private final ChangeWorkItemStatusUseCase changeStatus = new ChangeWorkItemStatusUseCase(items, workflows, assignments, audit, transactions,
			details, velocity, burndown);

	private final MoveToSprintUseCase move = new MoveToSprintUseCase(items, projects, audit, transactions, details, velocity, burndown);

	private final DeleteWorkItemUseCase delete = new DeleteWorkItemUseCase(items, audit, transactions, velocity, burndown);

	private final SetParentUseCase setParent = new SetParentUseCase(items, audit, transactions, details, burndown);

	private UUID project;

	private UUID sprint;

	@BeforeEach
	void aSprintOfAProject() {
		project = items.addProject("WAR");
		sprint = projects.add(project, "Sprint 1", "ACTIVE").sprintCode();
	}

	private UpdateWorkItem hours(UUID item, String estimated, String remaining) {
		return new UpdateWorkItem(admin, item, null, null, null, null, null, new BigDecimal(estimated), new BigDecimal(remaining));
	}

	@Test
	void editingTheRemainingHoursRecordsTheSprint() {
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());

		update.execute(hours(task.code(), "8", "5"));

		assertThat(snapshots.snapshots).containsExactly(sprint);
	}

	@Test
	void editingSomethingThatDoesNotChangeTheHoursRecordsNothing() {
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());

		update.execute(new UpdateWorkItem(admin, task.code(), "Renamed", null, null, null, null, null, null));

		assertThat(snapshots.snapshots).isEmpty();
	}

	@Test
	void finishingOrReopeningAnItemRecordsButAnOrdinaryStepDoesNot() {
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());

		changeStatus.execute(new ChangeStatus(admin, task.code(), "ACTIVE"));
		assertThat(snapshots.snapshots).as("NEW -> ACTIVE: nothing is done yet").isEmpty();

		changeStatus.execute(new ChangeStatus(admin, task.code(), "DONE"));
		assertThat(snapshots.snapshots).as("ACTIVE -> DONE: its hours are gone").containsExactly(sprint);
	}

	@Test
	void movingAnItemRecordsBothSprintsAndTheBacklogNone() {
		var other = projects.add(project, "Sprint 2", "PLANNED").sprintCode();
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());

		move.execute(new MoveToSprint(admin, task.code(), other));
		assertThat(snapshots.snapshots).containsExactly(sprint, other);

		snapshots.snapshots.clear();
		var backlogged = items.add(project, ItemType.TASK, "NEW", 0, null, admin.userCode());
		move.execute(new MoveToSprint(admin, backlogged.code(), sprint));
		assertThat(snapshots.snapshots).containsExactly(sprint);
	}

	@Test
	void deletingAnItemAndChangingItsParentRecordItsSprint() {
		var parent = items.add(project, ItemType.PBI, "NEW", 3, sprint, admin.userCode());
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());

		setParent.execute(new SetParent(admin, task.code(), parent.code()));
		assertThat(snapshots.snapshots).containsExactly(sprint);

		snapshots.snapshots.clear();
		delete.execute(admin, task.code());
		assertThat(snapshots.snapshots).containsExactly(sprint);
	}

	@Test
	void aFailedSnapshotNeverFailsTheChange() {
		var task = items.add(project, ItemType.TASK, "NEW", 0, sprint, admin.userCode());
		snapshots.failing = true;

		var result = update.execute(hours(task.code(), "8", "5"));

		assertThat(result.isSuccess()).isTrue();
		assertThat(items.stored.get(task.code()).remainingHours()).isEqualByComparingTo("5");
	}

}
