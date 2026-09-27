package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.service.ListWorkItemHistoryUseCase;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.support.Fakes;

class WorkItemHistoryTest {

	private final Fakes.Audit audit = new Fakes.Audit();

	private final ListWorkItemHistoryUseCase history = new ListWorkItemHistoryUseCase(audit);

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final Actor admin = new Actor(UUID.randomUUID(), OrganizationRole.ADMIN);

	private final UUID item = UUID.randomUUID();

	private void write(ChangeType type, String oldValue, String newValue) {
		audit.append(new AuditEntry(item, member.userCode(), type, type == ChangeType.CREATED ? null : "Status", oldValue, newValue));
	}

	@Test
	void listsTheNewestChangeFirstAndPagesThroughAllOfThem() {
		write(ChangeType.CREATED, null, "Story");
		write(ChangeType.STATE_CHANGED, "NEW", "APPROVED");
		write(ChangeType.STATE_CHANGED, "APPROVED", "COMMITTED");

		var first = history.execute(member, item, 0, 2).getValue();
		assertThat(first.total()).isEqualTo(3);
		assertThat(first.items()).extracting(entry -> entry.newValue()).containsExactly("COMMITTED", "APPROVED");
		assertThat(first.items().getFirst().changedBy().userCode()).isEqualTo(member.userCode());
		assertThat(first.items().getFirst().createdAt()).isAfter(first.items().getLast().createdAt());

		var second = history.execute(member, item, 1, 2).getValue();
		assertThat(second.items()).extracting(entry -> entry.type()).containsExactly(ChangeType.CREATED);
		assertThat(history.execute(member, item, 2, 2).getValue().items()).isEmpty();
	}

	@Test
	void anItemThatDoesNotExistHasNoHistory() {
		var result = history.execute(member, UUID.randomUUID(), 0, 50);

		assertThat(result.isFailure()).isTrue();
		assertThat(result.getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void theHistoryOfADeletedItemEndsWithItsDeletionAndIsForOwnersAndAdminsOnly() {
		write(ChangeType.CREATED, null, "Story");
		write(ChangeType.DELETED, "Story", null);
		audit.deleted.add(item);

		var forAdmin = history.execute(admin, item, 0, 50).getValue();
		assertThat(forAdmin.items()).extracting(entry -> entry.type()).containsExactly(ChangeType.DELETED, ChangeType.CREATED);

		var forMember = history.execute(member, item, 0, 50);
		assertThat(forMember.isFailure()).isTrue();
		assertThat(forMember.getError()).isInstanceOf(WorkItemError.WorkItemNotFound.class);
	}

	@Test
	void refusesAPageOrSizeOutOfRange() {
		write(ChangeType.CREATED, null, "Story");

		assertThat(history.execute(member, item, -1, 50).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(history.execute(member, item, 0, 0).getError()).isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(history.execute(member, item, 0, ListWorkItemHistoryUseCase.MAX_PAGE_SIZE + 1).getError())
				.isInstanceOf(WorkItemError.InvalidWorkItemData.class);
		assertThat(history.execute(member, item, 0, ListWorkItemHistoryUseCase.MAX_PAGE_SIZE).isSuccess()).isTrue();
	}

}
