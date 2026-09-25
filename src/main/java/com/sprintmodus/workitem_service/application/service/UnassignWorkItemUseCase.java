package com.sprintmodus.workitem_service.application.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.AssignmentError;
import com.sprintmodus.workitem_service.application.error.AssignmentError.AssignmentNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;

/** Removes an assignment (it is deactivated, so assigning the same user and role again brings it back) and records {@code UNASSIGNED}. */
@Service
public class UnassignWorkItemUseCase {

	private final AssignmentRepository assignments;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	public UnassignWorkItemUseCase(AssignmentRepository assignments, AuditRepository audit, TransactionRunner transactions) {
		this.assignments = assignments;
		this.audit = audit;
		this.transactions = transactions;
	}

	public Result<Unit, AssignmentError> execute(Actor actor, UUID workItemCode, UUID assignmentCode) {
		Assignment assignment = assignments.find(workItemCode, assignmentCode).orElse(null);
		if (assignment == null) {
			return Result.failure(new AssignmentNotFound());
		}
		return transactions.inTransaction(() -> {
			if (!assignments.unassign(workItemCode, assignmentCode)) {
				return Result.<Unit, AssignmentError>failure(new AssignmentNotFound());
			}
			audit.append(new AuditEntry(workItemCode, actor.userCode(), ChangeType.UNASSIGNED, "Assignee", assignment.label(), null,
					Map.of("userCode", assignment.userCode().toString(), "role", assignment.role().name())));
			return Result.<Unit, AssignmentError>success(Unit.VALUE);
		});
	}

}
