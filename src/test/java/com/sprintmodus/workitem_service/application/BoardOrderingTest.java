package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.ReorderWorkItem;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkItemQuery;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkItemQuery.SprintScope;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.service.ListWorkItemsUseCase;
import com.sprintmodus.workitem_service.application.service.ReorderWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.WorkItemDetails;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.service.BoardOrder;
import com.sprintmodus.workitem_service.support.Fakes;

/** The order of the cards of a board column: priority first, then the manual rank an owner or admin sets. */
class BoardOrderingTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Assignments assignments = new Fakes.Assignments(items);

	private final Fakes.Links links = new Fakes.Links(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final ReorderWorkItemUseCase reorder = new ReorderWorkItemUseCase(items, audit, transactions,
			new WorkItemDetails(items, assignments, links, workflows));

	private final ListWorkItemsUseCase list = new ListWorkItemsUseCase(items, assignments);

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final Actor admin = new Actor(UUID.randomUUID(), OrganizationRole.ADMIN);

	private UUID project;

	@BeforeEach
	void aProject() {
		project = items.addProject("WAR");
	}

	private WorkItem card(Priority priority) {
		WorkItem added = items.add(project, ItemType.PBI, "NEW", 1, null, admin.userCode());
		items.updateFields(added.code(), new com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository.FieldChanges(
				added.title(), null, null, priority, 1, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO), admin.userCode());
		return items.stored.get(added.code());
	}

	private List<UUID> column(boolean boardOrder) {
		var query = new WorkItemQuery(project, SprintScope.ANY, null, ItemType.PBI, "NEW", null, null, null, null, boardOrder, 0, 50);
		return list.execute(query).getValue().items().stream().map(item -> item.code()).toList();
	}

	// ------------------------------------------------------------------ the pure rule

	@Test
	void aCardIsPlacedBeforeAnotherOrLast() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

		assertThat(BoardOrder.place(List.of(a, b, c), c, a)).containsExactly(c, a, b);
		assertThat(BoardOrder.place(List.of(a, b, c), a, c)).containsExactly(b, a, c);
		assertThat(BoardOrder.place(List.of(a, b, c), a, null)).containsExactly(b, c, a);
		assertThat(BoardOrder.place(List.of(a, b, c), b, c)).as("already there").containsExactly(a, b, c);
	}

	@Test
	void aCardMustBeInTheGroupAndCannotGoBeforeItself() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();

		assertThatThrownBy(() -> BoardOrder.place(List.of(a), b, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BoardOrder.place(List.of(a, b), a, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BoardOrder.place(List.of(a, b), a, a)).isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------ the list

	@Test
	void theListIsNewestFirstUnlessTheBoardOrderIsAsked() {
		WorkItem first = card(Priority.LOW);
		WorkItem second = card(Priority.CRITICAL);

		assertThat(column(false)).containsExactly(second.code(), first.code());
	}

	@Test
	void theBoardOrderIsPriorityThenManualRankThenNumber() {
		WorkItem lowOld = card(Priority.LOW);
		WorkItem mediumA = card(Priority.MEDIUM);
		WorkItem mediumB = card(Priority.MEDIUM);
		WorkItem mediumC = card(Priority.MEDIUM);
		WorkItem critical = card(Priority.CRITICAL);

		assertThat(column(true)).as("never ranked: by number inside a priority")
				.containsExactly(critical.code(), mediumA.code(), mediumB.code(), mediumC.code(), lowOld.code());

		reorder.execute(new ReorderWorkItem(admin, mediumC.code(), mediumA.code()));

		assertThat(column(true)).containsExactly(critical.code(), mediumC.code(), mediumA.code(), mediumB.code(), lowOld.code());
	}

	@Test
	void everyRowCarriesItsAssigneesRankAndChildCount() {
		WorkItem item = card(Priority.HIGH);
		UUID ana = assignments.addUser("Ana Diaz");
		assignments.assign(item.code(), ana, AssignmentRole.DEV);

		var row = list.execute(new WorkItemQuery(project, SprintScope.ANY, null, null, null, null, null, null, null, true, 0, 50))
				.getValue().items().getFirst();

		assertThat(row.assignees()).singleElement().satisfies(a -> {
			assertThat(a.fullName()).isEqualTo("Ana Diaz");
			assertThat(a.role()).isEqualTo(AssignmentRole.DEV);
		});
		assertThat(row.boardRank()).isNull();
		assertThat(row.childCount()).isZero();
	}

	// ------------------------------------------------------------------ reordering

	@Test
	void reorderingRenumbersTheGroupAndAuditsTheMovedCard() {
		WorkItem a = card(Priority.MEDIUM);
		WorkItem b = card(Priority.MEDIUM);
		WorkItem c = card(Priority.MEDIUM);

		var result = reorder.execute(new ReorderWorkItem(admin, c.code(), a.code()));

		assertThat(result.isSuccess()).isTrue();
		assertThat(items.stored.get(c.code()).boardRank()).isEqualTo(1);
		assertThat(items.stored.get(a.code()).boardRank()).isEqualTo(2);
		assertThat(items.stored.get(b.code()).boardRank()).isEqualTo(3);
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.FIELD_CHANGED);
			assertThat(entry.field()).isEqualTo("BoardRank");
			assertThat(entry.oldValue()).isNull();
			assertThat(entry.newValue()).isEqualTo("1");
			assertThat(entry.changedBy()).isEqualTo(admin.userCode());
		});
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
	}

	@Test
	void aNullTargetSendsTheCardToTheEndOfItsGroup() {
		WorkItem a = card(Priority.MEDIUM);
		WorkItem b = card(Priority.MEDIUM);

		reorder.execute(new ReorderWorkItem(admin, a.code(), null));

		assertThat(column(true)).containsExactly(b.code(), a.code());
		assertThat(items.stored.get(a.code()).boardRank()).isEqualTo(2);
	}

	@Test
	void placingACardWhereItAlreadyIsAuditsNothing() {
		WorkItem a = card(Priority.MEDIUM);
		WorkItem b = card(Priority.MEDIUM);
		reorder.execute(new ReorderWorkItem(admin, a.code(), null));
		audit.entries.clear();

		reorder.execute(new ReorderWorkItem(admin, a.code(), null));

		assertThat(audit.entries).isEmpty();
		assertThat(column(true)).containsExactly(b.code(), a.code());
	}

	@Test
	void onlyOwnersAndAdminsReorder() {
		WorkItem a = card(Priority.MEDIUM);
		WorkItem b = card(Priority.MEDIUM);

		var result = reorder.execute(new ReorderWorkItem(member, b.code(), a.code()));

		assertThat(result.getError()).isInstanceOf(WorkItemError.NotAllowed.class);
		assertThat(items.stored.get(b.code()).boardRank()).isNull();
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void theTargetMustShareTheCardsProjectTypeStatusAndPriority() {
		WorkItem medium = card(Priority.MEDIUM);
		WorkItem high = card(Priority.HIGH);
		WorkItem otherStatus = items.add(project, ItemType.PBI, "ACTIVE", 1, null, admin.userCode());

		assertThat(reorder.execute(new ReorderWorkItem(admin, medium.code(), high.code())).getError())
				.isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(reorder.execute(new ReorderWorkItem(admin, medium.code(), otherStatus.code())).getError())
				.isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(reorder.execute(new ReorderWorkItem(admin, medium.code(), UUID.randomUUID())).getError())
				.isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(reorder.execute(new ReorderWorkItem(admin, medium.code(), medium.code())).getError())
				.isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(reorder.execute(new ReorderWorkItem(admin, UUID.randomUUID(), null)).getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
		assertThat(audit.entries).isEmpty();
	}

}
