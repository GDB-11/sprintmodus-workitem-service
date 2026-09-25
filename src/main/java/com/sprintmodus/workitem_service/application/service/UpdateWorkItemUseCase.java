package com.sprintmodus.workitem_service.application.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.UpdateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository.FieldChanges;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.service.EffortPointsService;
import com.sprintmodus.workitem_service.domain.service.WorkItemDiff;

/**
 * Edits a work item's own fields. Only what really changed is written, and each change is audited (title and
 * acceptance criteria as {@code FIELD_CHANGED}, description as {@code DESCRIPTION_EDITED}, points and hours as
 * {@code EFFORT_CHANGED}) in the same transaction. When completed effort in a sprint may have changed, the sprint's velocity
 * is refreshed afterwards.
 */
@Service
public class UpdateWorkItemUseCase {

	private final WorkItemRepository items;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	private final SprintVelocitySync velocity;

	private final BurndownRecorder burndown;

	public UpdateWorkItemUseCase(WorkItemRepository items, AuditRepository audit, TransactionRunner transactions,
			WorkItemDetails details, SprintVelocitySync velocity, BurndownRecorder burndown) {
		this.items = items;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
		this.velocity = velocity;
		this.burndown = burndown;
	}

	public Result<WorkItemResponse, WorkItemError> execute(UpdateWorkItem command) {
		WorkItem current = items.findByCode(command.workItemCode()).orElse(null);
		if (current == null) {
			return Result.failure(new WorkItemNotFound());
		}

		String title = command.title() == null ? current.title() : command.title().trim();
		if (title.isEmpty() || title.length() > CreateWorkItemUseCase.MAX_TITLE_LENGTH) {
			return invalid("title", "Enter a title of up to " + CreateWorkItemUseCase.MAX_TITLE_LENGTH + " characters.");
		}
		String description = command.description() == null ? current.description() : CreateWorkItemUseCase.text(command.description());
		String acceptanceCriteria = command.acceptanceCriteria() == null ? current.acceptanceCriteria()
				: CreateWorkItemUseCase.text(command.acceptanceCriteria());
		if (description != null && description.length() > CreateWorkItemUseCase.MAX_TEXT_LENGTH) {
			return invalid("description", "The text is too long.");
		}
		if (acceptanceCriteria != null && acceptanceCriteria.length() > CreateWorkItemUseCase.MAX_TEXT_LENGTH) {
			return invalid("acceptanceCriteria", "The text is too long.");
		}
		int points = command.effortPoints() == null ? current.effortPoints() : command.effortPoints();
		BigDecimal estimated = command.estimatedHours() == null ? current.estimatedHours() : command.estimatedHours();
		BigDecimal remaining = command.remainingHours() == null ? current.remainingHours() : command.remainingHours();
		var effort = EffortPointsService.validateEffort(points, estimated, remaining);
		if (effort.isFailure()) {
			return invalid(effort.getError().field(), effort.getError().message());
		}
		var priority = command.priority() == null ? current.priority() : command.priority();

		var entries = WorkItemDiff.between(current,
				new WorkItemDiff.After(title, description, acceptanceCriteria, priority, points, estimated, remaining),
				command.actor().userCode());
		if (entries.isEmpty()) {
			return Result.success(details.assemble(current, List.of()));
		}

		boolean applied = transactions.inTransaction(() -> {
			if (!items.updateFields(current.code(), new FieldChanges(title, description, acceptanceCriteria, priority, points,
					estimated, remaining), command.actor().userCode())) {
				return false;
			}
			entries.forEach(audit::append);
			return true;
		});
		if (!applied) {
			return Result.failure(new WorkItemNotFound());
		}

		boolean effortChanged = entries.stream().anyMatch(entry -> entry.type() == ChangeType.EFFORT_CHANGED
				&& "EffortPoints".equals(entry.field()));
		List<Warning> warnings = effortChanged ? velocity.refresh(current.sprintCode()) : List.of();
		if (entries.stream().anyMatch(entry -> entry.type() == ChangeType.EFFORT_CHANGED && "RemainingHours".equals(entry.field()))) {
			burndown.record(current.sprintCode());
		}
		return details.load(current.code(), warnings).<Result<WorkItemResponse, WorkItemError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

	private static Result<WorkItemResponse, WorkItemError> invalid(String field, String message) {
		return Result.failure(new InvalidWorkItemData(field, message));
	}

}
