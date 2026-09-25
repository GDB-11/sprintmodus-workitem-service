package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.SetParent;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Gives a work item a new parent, or none. The hard rules reject (same project, no cycles); a non-recommended parent type
 * is allowed and comes back as a warning. Recorded as {@code PARENT_CHANGED}.
 */
@Service
public class SetParentUseCase {

	private final WorkItemRepository items;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final WorkItemDetails details;

	private final BurndownRecorder burndown;

	public SetParentUseCase(WorkItemRepository items, AuditRepository audit, TransactionRunner transactions, WorkItemDetails details,
			BurndownRecorder burndown) {
		this.items = items;
		this.audit = audit;
		this.transactions = transactions;
		this.details = details;
		this.burndown = burndown;
	}

	public Result<WorkItemResponse, WorkItemError> execute(SetParent command) {
		WorkItem item = items.findByCode(command.workItemCode()).orElse(null);
		if (item == null) {
			return Result.failure(new WorkItemNotFound());
		}
		if (Objects.equals(item.parentCode(), command.parentCode())) {
			return Result.success(details.assemble(item, List.of()));
		}

		List<Warning> warnings = List.of();
		if (command.parentCode() != null) {
			var parent = ParentRules.check(items, item.type(), item.code(), item.projectCode(), command.parentCode());
			if (parent.isFailure()) {
				return Result.failure(parent.getError());
			}
			warnings = parent.getValue();
		}

		String oldParent = item.parentCode() == null ? null : item.parentCode().toString();
		String newParent = command.parentCode() == null ? null : command.parentCode().toString();
		boolean applied = transactions.inTransaction(() -> {
			if (!items.setParent(item.code(), command.parentCode(), command.actor().userCode())) {
				return false;
			}
			audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.PARENT_CHANGED, "Parent", oldParent, newParent,
					Map.of("displayKey", item.displayKey())));
			return true;
		});
		if (!applied) {
			return Result.failure(new WorkItemNotFound());
		}
		burndown.record(item.sprintCode()); // an item with a parent in its sprint counts through its children, not itself
		return details.load(item.code(), warnings).<Result<WorkItemResponse, WorkItemError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

}
