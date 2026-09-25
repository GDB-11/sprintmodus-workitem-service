package com.sprintmodus.workitem_service.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.error.WorkItemError.ProjectNotFound;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkflowNotConfigured;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository.NewWorkItem;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.domain.service.EffortPointsService;

/**
 * Creates a work item in its type's initial status, numbered from its project's sequence, and records {@code CREATED} in
 * the same transaction. A non-standard parent is allowed and comes back as a warning.
 */
@Service
public class CreateWorkItemUseCase {

	static final int MAX_TITLE_LENGTH = 500;

	static final int MAX_TEXT_LENGTH = 50_000;

	private final WorkItemRepository items;

	private final WorkflowRepository workflows;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	public CreateWorkItemUseCase(WorkItemRepository items, WorkflowRepository workflows, AuditRepository audit,
			TransactionRunner transactions, WorkItemDetails details) {
		this.items = items;
		this.workflows = workflows;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
	}

	public Result<WorkItemResponse, WorkItemError> execute(CreateWorkItem command) {
		if (command.projectCode() == null) {
			return invalid("projectCode", "Choose the project of the work item.");
		}
		if (command.type() == null) {
			return invalid("type", "Choose the type of the work item.");
		}
		String title = command.title() == null ? "" : command.title().trim();
		if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
			return invalid("title", "Enter a title of up to " + MAX_TITLE_LENGTH + " characters.");
		}
		String description = text(command.description());
		String acceptanceCriteria = text(command.acceptanceCriteria());
		if (tooLong(description) || tooLong(acceptanceCriteria)) {
			return invalid(tooLong(description) ? "description" : "acceptanceCriteria", "The text is too long.");
		}
		int points = command.effortPoints() == null ? 0 : command.effortPoints();
		BigDecimal estimated = command.estimatedHours() == null ? BigDecimal.ZERO : command.estimatedHours();
		BigDecimal remaining = command.remainingHours() == null ? estimated : command.remainingHours();
		var effort = EffortPointsService.validateEffort(points, estimated, remaining);
		if (effort.isFailure()) {
			return invalid(effort.getError().field(), effort.getError().message());
		}

		List<Warning> warnings = List.of();
		if (command.parentCode() != null) {
			var parent = ParentRules.check(items, command.type(), null, command.projectCode(), command.parentCode());
			if (parent.isFailure()) {
				return Result.failure(parent.getError());
			}
			warnings = parent.getValue();
		}

		Workflow.WorkflowStatus initial = workflows.find(command.type()).initialStatus().orElse(null);
		if (initial == null) {
			return Result.failure(new WorkflowNotConfigured());
		}

		NewWorkItem newItem = new NewWorkItem(command.projectCode(), command.type(), initial.code(), title, description,
				acceptanceCriteria, command.priority() == null ? Priority.MEDIUM : command.priority(), command.parentCode(), points,
				estimated, remaining, command.actor().userCode());
		WorkItem created = transactions.inTransaction(() -> {
			WorkItem item = items.insert(newItem).orElse(null);
			if (item != null) {
				audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.CREATED, null, null, title,
						Map.of("displayKey", item.displayKey(), "type", item.type().name(), "status", initial.code())));
			}
			return item;
		});
		if (created == null) {
			return Result.failure(new ProjectNotFound());
		}
		return Result.success(details.assemble(created, warnings));
	}

	private static Result<WorkItemResponse, WorkItemError> invalid(String field, String message) {
		return Result.failure(new InvalidWorkItemData(field, message));
	}

	/** Blank text is stored as none. */
	static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static boolean tooLong(String value) {
		return value != null && value.length() > MAX_TEXT_LENGTH;
	}

}
