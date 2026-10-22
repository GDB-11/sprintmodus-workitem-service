package com.sprintmodus.workitem_service.application.service;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.Assign;
import com.sprintmodus.workitem_service.application.dto.Responses.AssigneeResponse;
import com.sprintmodus.workitem_service.application.error.AssignmentError;
import com.sprintmodus.workitem_service.application.error.AssignmentError.AlreadyAssigned;
import com.sprintmodus.workitem_service.application.error.AssignmentError.InvalidAssignment;
import com.sprintmodus.workitem_service.application.error.AssignmentError.UserNotFound;
import com.sprintmodus.workitem_service.application.error.AssignmentError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.DomainEventPublisher;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.NotificationCreated;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.WorkItemAssigned;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Notification;
import com.sprintmodus.workitem_service.domain.model.NotificationType;

/**
 * Assigns a user to a work item in a role (DEV, QA, PO, PM or SCRUM_MASTER) and records {@code ASSIGNED} with the acting
 * user and, in the same transaction, one {@code ASSIGNED} notification for the assignee (none when assigning oneself), then
 * publishes {@link WorkItemAssigned} and {@link NotificationCreated} once committed. One user may hold several roles on an item, each once.
 */
@Service
public class AssignWorkItemUseCase {

	private final WorkItemRepository items;

	private final AssignmentRepository assignments;

	private final AuditRepository audit;

	private final NotificationRepository notifications;

	private final TransactionRunner transactions;

	private final DomainEventPublisher events;

	public AssignWorkItemUseCase(WorkItemRepository items, AssignmentRepository assignments, AuditRepository audit,
			NotificationRepository notifications, TransactionRunner transactions, DomainEventPublisher events) {
		this.items = items;
		this.assignments = assignments;
		this.audit = audit;
		this.notifications = notifications;
		this.transactions = transactions;
		this.events = events;
	}

	/** What the transaction produced, so the events can be published once it has committed. */
	private record Saved(AssigneeResponse assignee, Optional<Notification> notification) {
	}

	public Result<AssigneeResponse, AssignmentError> execute(Assign command) {
		if (command.userCode() == null) {
			return Result.failure(new InvalidAssignment("userCode", "Choose the user to assign."));
		}
		if (command.role() == null) {
			return Result.failure(new InvalidAssignment("role", "Choose the role of the assignment."));
		}
		if (items.findByCode(command.workItemCode()).isEmpty()) {
			return Result.failure(new WorkItemNotFound());
		}

		Result<Saved, AssignmentError> result = transactions.inTransaction(() -> {
			if (assignments.isAssigned(command.workItemCode(), command.userCode(), command.role())) {
				return Result.<Saved, AssignmentError>failure(new AlreadyAssigned());
			}
			Assignment assignment = assignments.assign(command.workItemCode(), command.userCode(), command.role()).orElse(null);
			if (assignment == null) {
				// the item was checked above, so the user is what is missing (or it was deleted in the meantime)
				return Result.<Saved, AssignmentError>failure(items.findByCode(command.workItemCode()).isEmpty()
						? new WorkItemNotFound() : new UserNotFound());
			}
			audit.append(new AuditEntry(command.workItemCode(), command.actor().userCode(), ChangeType.ASSIGNED, "Assignee", null,
					assignment.label(), Map.of("userCode", assignment.userCode().toString(), "fullName", assignment.fullName(), "role", assignment.role().name())));
			// assigning oneself stays in the audit trail but notifies nobody
			Optional<Notification> notification = assignment.userCode().equals(command.actor().userCode()) ? Optional.empty()
					: notifications.create(NotificationType.ASSIGNED, assignment.userCode(), command.workItemCode(), null,
							command.actor().userCode());
			return Result.<Saved, AssignmentError>success(new Saved(AssigneeResponse.from(assignment), notification));
		});
		return result.tap(saved -> {
			AssigneeResponse assigned = saved.assignee();
			events.publish(new WorkItemAssigned(command.workItemCode(), command.actor().userCode(), assigned.code(),
					assigned.userCode(), assigned.role()));
			saved.notification().ifPresent(notification -> events.publish(new NotificationCreated(command.workItemCode(),
					command.actor().userCode(), notification.code(), assigned.userCode(), notification.type())));
		}).map(Saved::assignee);
	}

}
