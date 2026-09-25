package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.ChangeStatus;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError.InvalidTransition;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError.NotAllowed;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError.StatusChangedConcurrently;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError.UnknownStatus;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowTransition;

/**
 * Moves a work item along its type's configured workflow, forward, or backward where the workflow allows it. The change and
 * its {@code STATE_CHANGED} audit entry are written in one transaction, and the update only applies if the item is still in
 * the status it was read in, so two concurrent moves cannot both succeed. Entering or leaving a terminal status changes what
 * the item's sprint has completed, so the sprint's velocity is refreshed afterwards.
 */
@Service
public class ChangeWorkItemStatusUseCase {

	private final WorkItemRepository items;

	private final WorkflowRepository workflows;

	private final AssignmentRepository assignments;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	private final SprintVelocitySync velocity;

	private final BurndownRecorder burndown;

	public ChangeWorkItemStatusUseCase(WorkItemRepository items, WorkflowRepository workflows, AssignmentRepository assignments,
			AuditRepository audit, TransactionRunner transactions, WorkItemDetails details, SprintVelocitySync velocity,
			BurndownRecorder burndown) {
		this.items = items;
		this.workflows = workflows;
		this.assignments = assignments;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
		this.velocity = velocity;
		this.burndown = burndown;
	}

	public Result<WorkItemResponse, StatusTransitionError> execute(ChangeStatus command) {
		WorkItem item = items.findByCode(command.workItemCode()).orElse(null);
		if (item == null) {
			return Result.failure(new WorkItemNotFound());
		}
		String requested = command.status() == null ? "" : command.status().trim().toUpperCase(Locale.ROOT);
		var workflow = workflows.find(item.type());
		WorkflowStatus target = workflow.status(requested).filter(WorkflowStatus::active).orElse(null);
		if (target == null) {
			return Result.failure(new UnknownStatus(requested));
		}
		WorkflowTransition transition = workflow.transitionFor(item.status().code(), target.code()).orElse(null);
		if (transition == null) {
			return Result.failure(new InvalidTransition(item.status().displayName(), target.displayName()));
		}
		AssignmentRole requiredRole = transition.requiredRole();
		if (requiredRole != null && !command.actor().canAdminister()
				&& !assignments.isAssigned(item.code(), command.actor().userCode(), requiredRole)) {
			return Result.failure(new NotAllowed(requiredRole));
		}

		boolean changed = transactions.inTransaction(() -> {
			if (!items.changeStatus(item.code(), item.status().code(), target.code(), command.actor().userCode())) {
				return false;
			}
			audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.STATE_CHANGED, "Status",
					item.status().code(), target.code(),
					Map.of("from", item.status().displayName(), "to", target.displayName())));
			return true;
		});
		if (!changed) {
			// nothing was written: the item was moved or deleted by someone else since it was read
			return Result.failure(items.findByCode(item.code()).isEmpty() ? new WorkItemNotFound() : new StatusChangedConcurrently());
		}

		List<Warning> warnings = item.status().terminal() != target.terminal() ? velocity.refresh(item.sprintCode()) : List.of();
		if (item.status().terminal() != target.terminal()) {
			burndown.record(item.sprintCode()); // a finished item has nothing left; a reopened one has its hours again
		}
		return details.load(item.code(), warnings).<Result<WorkItemResponse, StatusTransitionError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

}
