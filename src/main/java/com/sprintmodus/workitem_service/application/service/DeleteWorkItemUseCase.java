package com.sprintmodus.workitem_service.application.service;

import java.util.ArrayList;
import java.util.List;
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
 * Soft-deletes a work item together with everything below it, recording {@code DELETED} for each. The creator, an owner
 * or an admin may; when the item has descendants, a non-admin must also have created every one of them (they are
 * deleting other people's work otherwise, which {@link HasChildren} reports).
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
		// deepest first, so no item is ever deleted while an active child still points at it
		List<WorkItem> doomed = new ArrayList<>();
		for (UUID code : items.findActiveDescendantCodes(item.code()).reversed()) {
			items.findByCode(code).ifPresent(doomed::add);
		}
		if (!actor.canAdminister() && doomed.stream().anyMatch(d -> !d.createdBy().userCode().equals(actor.userCode()))) {
			return Result.failure(new HasChildren());
		}
		doomed.add(item);

		List<WorkItem> deleted = transactions.inTransaction(() -> {
			List<WorkItem> done = new ArrayList<>();
			for (WorkItem target : doomed) {
				if (items.softDelete(target.code(), actor.userCode())) {
					audit.append(new AuditEntry(target.code(), actor.userCode(), ChangeType.DELETED, null, target.title(), null,
							java.util.Map.of("displayKey", target.displayKey())));
					done.add(target);
				}
			}
			return done;
		});
		if (deleted.stream().noneMatch(d -> d.code().equals(item.code()))) {
			return Result.failure(new WorkItemNotFound());
		}
		// what the items counted for in their sprints is gone
		deleted.stream().map(WorkItem::sprintCode).filter(java.util.Objects::nonNull).distinct().forEach(sprint -> {
			velocity.refresh(sprint);
			burndown.record(sprint);
		});
		return Result.success(Unit.VALUE);
	}

}
