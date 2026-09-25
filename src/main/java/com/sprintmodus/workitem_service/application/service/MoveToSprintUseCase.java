package com.sprintmodus.workitem_service.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.MoveToSprint;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.SprintMoveError;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.NotAllowed;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.ProjectServiceUnavailable;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.SprintClosed;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.SprintNotFound;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.SprintOfAnotherProject;
import com.sprintmodus.workitem_service.application.error.SprintMoveError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway.SprintInfo;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Moves a work item to a sprint, or back to the backlog; planning sprints is for organization owners and admins. project-service owns sprints, so the target is checked through it
 * (it must exist, belong to the item's project, and not be closed); the database change and its {@code SPRINT_CHANGED}
 * audit entry are one transaction; and the velocity of <em>both</em> sprints is recomputed afterwards, since the item's
 * completed effort leaves one and joins the other.
 */
@Service
public class MoveToSprintUseCase {

	private final WorkItemRepository items;

	private final ProjectServiceGateway projects;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	private final SprintVelocitySync velocity;

	private final BurndownRecorder burndown;

	public MoveToSprintUseCase(WorkItemRepository items, ProjectServiceGateway projects, AuditRepository audit,
			TransactionRunner transactions, WorkItemDetails details, SprintVelocitySync velocity,
			BurndownRecorder burndown) {
		this.items = items;
		this.projects = projects;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
		this.velocity = velocity;
		this.burndown = burndown;
	}

	public Result<WorkItemResponse, SprintMoveError> execute(MoveToSprint command) {
		if (!command.actor().canAdminister()) {
			return Result.failure(new NotAllowed());
		}
		WorkItem item = items.findByCode(command.workItemCode()).orElse(null);
		if (item == null) {
			return Result.failure(new WorkItemNotFound());
		}
		if (Objects.equals(item.sprintCode(), command.sprintCode())) {
			return Result.success(details.assemble(item, List.of()));
		}

		Map<String, String> data = new HashMap<>();
		data.put("displayKey", item.displayKey());
		if (command.sprintCode() != null) {
			// Outside any transaction: a slow project-service must not hold database locks
			var sprint = projects.getSprint(command.sprintCode());
			if (sprint.isFailure()) {
				return Result.failure(sprint.getError() == ProjectServiceGateway.Failure.NOT_FOUND ? new SprintNotFound()
						: new ProjectServiceUnavailable());
			}
			SprintInfo target = sprint.getValue();
			if (!target.projectCode().equals(item.projectCode())) {
				return Result.failure(new SprintOfAnotherProject());
			}
			if (target.isClosed()) {
				return Result.failure(new SprintClosed());
			}
			data.put("sprint", target.name());
		}

		String oldSprint = item.sprintCode() == null ? null : item.sprintCode().toString();
		String newSprint = command.sprintCode() == null ? null : command.sprintCode().toString();
		boolean moved = transactions.inTransaction(() -> {
			if (!items.setSprint(item.code(), command.sprintCode(), command.actor().userCode())) {
				return false;
			}
			audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.SPRINT_CHANGED, "Sprint", oldSprint, newSprint, data));
			return true;
		});
		if (!moved) {
			return Result.failure(new WorkItemNotFound());
		}

		var warnings = velocity.refresh(item.sprintCode(), command.sprintCode());
		burndown.record(item.sprintCode(), command.sprintCode());
		return details.load(item.code(), warnings).<Result<WorkItemResponse, SprintMoveError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

}
