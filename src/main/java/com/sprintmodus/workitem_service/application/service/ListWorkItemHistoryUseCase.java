package com.sprintmodus.workitem_service.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Responses.HistoryEntryResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.HistoryPage;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;

/**
 * Reads the history of a work item, newest change first, a page at a time. It is a read: it writes no audit entry. The
 * history of a deleted item (which ends with its deletion) is for owners and admins only; for everyone else a deleted item
 * does not exist, exactly as for its detail.
 */
@Service
public class ListWorkItemHistoryUseCase {

	public static final int MAX_PAGE_SIZE = 200;

	private final AuditRepository audit;

	public ListWorkItemHistoryUseCase(AuditRepository audit) {
		this.audit = audit;
	}

	public Result<HistoryPage, WorkItemError> execute(Actor actor, UUID workItemCode, int page, int size) {
		if (page < 0) {
			return Result.failure(new InvalidWorkItemData("page", "The page number cannot be negative."));
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			return Result.failure(new InvalidWorkItemData("size", "The page size must be between 1 and " + MAX_PAGE_SIZE + "."));
		}
		return audit.history(workItemCode, page, size).filter(history -> history.itemActive() || actor.canAdminister())
				.<Result<HistoryPage, WorkItemError>>map(history -> Result.success(new HistoryPage(
						history.entries().stream().map(HistoryEntryResponse::from).toList(), history.total(), page, size)))
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

}
