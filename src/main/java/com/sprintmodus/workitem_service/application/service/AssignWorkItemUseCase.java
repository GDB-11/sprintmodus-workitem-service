package com.sprintmodus.workitem_service.application.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.Assign;
import com.sprintmodus.workitem_service.application.dto.Responses.AssigneeResponse;
import com.sprintmodus.workitem_service.application.error.AssignmentError;
import com.sprintmodus.workitem_service.application.error.AssignmentError.AlreadyAssigned;
import com.sprintmodus.workitem_service.application.error.AssignmentError.InvalidAssignment;
import com.sprintmodus.workitem_service.application.error.AssignmentError.UserNotFound;
import com.sprintmodus.workitem_service.application.error.AssignmentError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;

/**
 * Assigns a user to a work item in a role (DEV, QA, PO, PM or SCRUM_MASTER) and records {@code ASSIGNED} with the acting
 * user. One user may hold several roles on an item, each once.
 */
@Service
public class AssignWorkItemUseCase {

	private final WorkItemRepository items;

	private final AssignmentRepository assignments;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	public AssignWorkItemUseCase(WorkItemRepository items, AssignmentRepository assignments, AuditRepository audit,
			TransactionRunner transactions) {
		this.items = items;
		this.assignments = assignments;
		this.audit = audit;
		this.transactions = transactions;
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

		return transactions.inTransaction(() -> {
			if (assignments.isAssigned(command.workItemCode(), command.userCode(), command.role())) {
				return Result.<AssigneeResponse, AssignmentError>failure(new AlreadyAssigned());
			}
			Assignment assignment = assignments.assign(command.workItemCode(), command.userCode(), command.role()).orElse(null);
			if (assignment == null) {
				// the item was checked above, so the user is what is missing (or it was deleted in the meantime)
				return Result.<AssigneeResponse, AssignmentError>failure(items.findByCode(command.workItemCode()).isEmpty()
						? new WorkItemNotFound() : new UserNotFound());
			}
			audit.append(new AuditEntry(command.workItemCode(), command.actor().userCode(), ChangeType.ASSIGNED, "Assignee", null,
					assignment.label(), Map.of("userCode", assignment.userCode().toString(), "role", assignment.role().name())));
			return Result.<AssigneeResponse, AssignmentError>success(AssigneeResponse.from(assignment));
		});
	}

}
