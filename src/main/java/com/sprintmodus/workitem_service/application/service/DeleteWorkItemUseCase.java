package com.sprintmodus.workitem_service.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.HasChildren;
import com.sprintmodus.workitem_service.application.error.WorkItemError.NotAllowed;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Soft-deletes a work item and records {@code DELETED}. The creator, an owner or an admin may; an item that still has
 * children cannot be deleted, so nothing is left hanging under a deleted parent.
 */
@Service
public class DeleteWorkItemUseCase {

	private final WorkItemRepository items;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final SprintVelocitySync velocity;

	private final BurndownRecorder burndown;

	public DeleteWorkItemUseCase(WorkItemRepository items, AuditRepository audit, TransactionRunner transactions,
			SprintVelocitySync velocity, BurndownRecorder burndown) {
		this.items = items;
		this.audit = audit;
		this.transactions = transactions;
		this.velocity = velocity;
		this.burndown = burndown;
	}

	public Result<Unit, WorkItemError> execute(Actor actor, UUID workItemCode) {
		WorkItem item = items.findByCode(workItemCode).orElse(null);
		if (item == null) {
			return Result.failure(new WorkItemNotFound());
		}
		if (!actor.canAdminister() && !item.createdBy().userCode().equals(actor.userCode())) {
			return Result.failure(new NotAllowed());
		}
		if (items.countActiveChildren(item.code()) > 0) {
			return Result.failure(new HasChildren());
		}

		boolean deleted = transactions.inTransaction(() -> {
			if (!items.softDelete(item.code(), actor.userCode())) {
				return false;
			}
			audit.append(new AuditEntry(item.code(), actor.userCode(), ChangeType.DELETED, null, item.title(), null,
					java.util.Map.of("displayKey", item.displayKey())));
			return true;
		});
		if (!deleted) {
			return Result.failure(new WorkItemNotFound());
		}
		// what the item counted for in its sprint is gone
		velocity.refresh(item.sprintCode());
		burndown.record(item.sprintCode());
		return Result.success(Unit.VALUE);
	}

}
