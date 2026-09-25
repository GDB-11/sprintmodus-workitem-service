package com.sprintmodus.workitem_service.application.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.UpsertWorkflow;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkflowResponse;
import com.sprintmodus.workitem_service.application.error.WorkflowError;
import com.sprintmodus.workitem_service.application.error.WorkflowError.InvalidWorkflow;
import com.sprintmodus.workitem_service.application.error.WorkflowError.NotAllowed;
import com.sprintmodus.workitem_service.application.error.WorkflowError.StatusInUse;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowTransition;

/**
 * Replaces the workflow of an item type (owners and admins only). Statuses are matched by code, so items keep their status
 * when it stays; a status the new workflow drops is retired, never deleted, and only if no active work item is still in it,
 * because such an item would have no way out.
 */
@Service
public class UpsertWorkflowUseCase {

	static final int MAX_STATUSES = 50;

	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

	private final WorkflowRepository workflows;

	private final TransactionRunner transactions;

	public UpsertWorkflowUseCase(WorkflowRepository workflows, TransactionRunner transactions) {
		this.workflows = workflows;
		this.transactions = transactions;
	}

	public Result<WorkflowResponse, WorkflowError> execute(UpsertWorkflow command) {
		if (!command.actor().canAdminister()) {
			return Result.failure(new NotAllowed());
		}
		if (command.itemType() == null) {
			return invalid("itemType", "Choose the work item type.");
		}
		var parsed = parse(command);
		if (parsed.isFailure()) {
			return Result.failure(parsed.getError());
		}
		Workflow workflow = parsed.getValue();

		return transactions.inTransaction(() -> {
			// Checked inside the transaction that replaces the workflow, so it cannot be outrun
			Set<String> kept = new HashSet<>();
			workflow.statuses().forEach(status -> kept.add(status.code()));
			for (var usage : workflows.usage(command.itemType())) {
				if (!kept.contains(usage.statusCode())) {
					return Result.<WorkflowResponse, WorkflowError>failure(new StatusInUse(usage.statusCode(), usage.items()));
				}
			}
			workflows.replace(workflow);
			return Result.<WorkflowResponse, WorkflowError>success(WorkflowResponse.from(workflows.find(command.itemType())));
		});
	}

	private Result<Workflow, WorkflowError> parse(UpsertWorkflow command) {
		var definitions = command.statuses();
		if (definitions == null || definitions.isEmpty()) {
			return invalid("statuses", "A workflow needs at least one status.");
		}
		if (definitions.size() > MAX_STATUSES) {
			return invalid("statuses", "A workflow can have at most " + MAX_STATUSES + " statuses.");
		}

		List<WorkflowStatus> statuses = new ArrayList<>();
		Set<String> codes = new HashSet<>();
		int position = 1;
		for (var definition : definitions) {
			String code = definition.code() == null ? "" : definition.code().trim().toUpperCase(Locale.ROOT);
			if (!CODE.matcher(code).matches()) {
				return invalid("statuses", "Status codes use uppercase letters, digits and underscores, starting with a letter (e.g. IN_PROGRESS).");
			}
			if (!codes.add(code)) {
				return invalid("statuses", "Status " + code + " appears more than once.");
			}
			String name = definition.displayName() == null ? "" : definition.displayName().trim();
			if (name.isEmpty() || name.length() > 100) {
				return invalid("statuses", "Status " + code + " needs a display name of up to 100 characters.");
			}
			int order = definition.order() == null ? position : definition.order();
			if (order < 1) {
				return invalid("statuses", "The order of status " + code + " must be 1 or more.");
			}
			statuses.add(new WorkflowStatus(code, name, order, Boolean.TRUE.equals(definition.terminal()), true));
			position++;
		}
		if (statuses.stream().noneMatch(WorkflowStatus::terminal)) {
			return invalid("statuses", "Mark at least one status as terminal (a final status).");
		}

		List<WorkflowTransition> transitions = new ArrayList<>();
		Set<String> pairs = new HashSet<>();
		for (var definition : command.transitions() == null ? List.<com.sprintmodus.workitem_service.application.dto.Commands.WorkflowTransitionDefinition>of() : command.transitions()) {
			String from = definition.from() == null ? "" : definition.from().trim().toUpperCase(Locale.ROOT);
			String to = definition.to() == null ? "" : definition.to().trim().toUpperCase(Locale.ROOT);
			if (!codes.contains(from) || !codes.contains(to)) {
				return invalid("transitions", "A transition must connect two statuses of this workflow (" + from + " to " + to + ").");
			}
			if (from.equals(to)) {
				return invalid("transitions", "A transition cannot lead from " + from + " to itself.");
			}
			if (!pairs.add(from + ">" + to)) {
				return invalid("transitions", "Transition " + from + " to " + to + " appears more than once.");
			}
			AssignmentRole requiredRole = null;
			if (definition.requiredRole() != null && !definition.requiredRole().isBlank()) {
				try {
					requiredRole = AssignmentRole.valueOf(definition.requiredRole().trim().toUpperCase(Locale.ROOT));
				}
				catch (IllegalArgumentException e) {
					return invalid("transitions", "Unknown role '" + definition.requiredRole() + "' for transition " + from + " to " + to + ".");
				}
			}
			transitions.add(new WorkflowTransition(from, to, Boolean.TRUE.equals(definition.allowedBackward()), requiredRole));
		}
		return Result.success(new Workflow(command.itemType(), statuses, transitions));
	}

	private static <T> Result<T, WorkflowError> invalid(String field, String message) {
		return Result.failure(new InvalidWorkflow(field, message));
	}

}
