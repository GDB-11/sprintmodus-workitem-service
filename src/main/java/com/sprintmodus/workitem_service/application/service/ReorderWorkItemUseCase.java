package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.ReorderWorkItem;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.error.WorkItemError.NotAllowed;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.service.BoardOrder;

/**
 * Sets the manual order of a card among the cards of the same project, type, status and priority (the order of a board
 * column is: priority first, then this). Only organization owners and admins do it. The group is locked, renumbered
 * {@code 1..n} in its new order (cards that keep their number are not touched) and the moved card's change is audited as a
 * {@code FIELD_CHANGED} of {@code BoardRank}, all in one transaction.
 */
@Service
public class ReorderWorkItemUseCase {

	private static final String FIELD = "BoardRank";

	private final WorkItemRepository items;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	public ReorderWorkItemUseCase(WorkItemRepository items, AuditRepository audit, TransactionRunner transactions, WorkItemDetails details) {
		this.items = items;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
	}

	public Result<WorkItemResponse, WorkItemError> execute(ReorderWorkItem command) {
		if (!command.actor().canAdminister()) {
			return Result.failure(new NotAllowed());
		}
		WorkItem item = items.findByCode(command.workItemCode()).orElse(null);
		if (item == null) {
			return Result.failure(new WorkItemNotFound());
		}
		UUID before = command.beforeCode();
		if (before != null) {
			WorkItem target = items.findByCode(before).orElse(null);
			if (target == null || !sameGroup(item, target)) {
				return Result.failure(new InvalidWorkItemData("beforeCode",
						"Choose a card of the same project, type, status and priority."));
			}
			if (before.equals(item.code())) {
				return Result.failure(new InvalidWorkItemData("beforeCode", "A card cannot be placed before itself."));
			}
		}

		boolean reordered = transactions.inTransaction(() -> {
			List<UUID> group = items.lockRankGroup(item.projectCode(), item.type(), item.status().code(), item.priority());
			if (!group.contains(item.code()) || (before != null && !group.contains(before))) {
				return false; // changed under our feet: another move took the card out of the group
			}
			List<UUID> placed = BoardOrder.place(group, item.code(), before);
			for (int i = 0; i < placed.size(); i++) {
				items.setBoardRank(placed.get(i), i + 1);
			}
			int newRank = placed.indexOf(item.code()) + 1;
			String oldRank = item.boardRank() == null ? null : item.boardRank().toString();
			if (!String.valueOf(newRank).equals(oldRank)) {
				audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.FIELD_CHANGED, FIELD, oldRank,
						String.valueOf(newRank)));
			}
			return true;
		});
		if (!reordered) {
			return Result.failure(new InvalidWorkItemData("beforeCode", "The cards changed while you were ordering them. Try again."));
		}
		return details.load(item.code(), List.of()).<Result<WorkItemResponse, WorkItemError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

	private static boolean sameGroup(WorkItem a, WorkItem b) {
		return a.projectCode().equals(b.projectCode()) && a.type() == b.type() && a.status().code().equals(b.status().code())
				&& a.priority() == b.priority();
	}

}
