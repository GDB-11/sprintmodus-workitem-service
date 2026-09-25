package com.sprintmodus.workitem_service.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkItemQuery;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemPage;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemSummary;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository.Filter;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/** Lists the active work items matching a filter, a page at a time, each with its assignees. */
@Service
public class ListWorkItemsUseCase {

	public static final int MAX_PAGE_SIZE = 200;

	private final WorkItemRepository items;

	private final AssignmentRepository assignments;

	public ListWorkItemsUseCase(WorkItemRepository items, AssignmentRepository assignments) {
		this.items = items;
		this.assignments = assignments;
	}

	public Result<WorkItemPage, WorkItemError> execute(WorkItemQuery query) {
		if (query.page() < 0) {
			return Result.failure(new InvalidWorkItemData("page", "The page number cannot be negative."));
		}
		if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
			return Result.failure(new InvalidWorkItemData("size", "The page size must be between 1 and " + MAX_PAGE_SIZE + "."));
		}
		var scope = switch (query.sprintScope()) {
			case ANY -> Filter.SprintScope.ANY;
			case BACKLOG -> Filter.SprintScope.BACKLOG;
			case SPRINT -> Filter.SprintScope.SPRINT;
		};
		Filter filter = new Filter(query.projectCode(), scope, query.sprintCode(), query.type(), normalize(query.status()),
				query.parentCode(), query.assigneeCode(), query.priority(), query.text(), query.boardOrder());

		var page = items.search(filter, query.page(), query.size());
		var assignees = assignments.findByWorkItems(page.items().stream().map(WorkItem::code).toList());
		return Result.success(new WorkItemPage(
				page.items().stream().map(item -> WorkItemSummary.from(item, assignees.getOrDefault(item.code(), List.of()))).toList(),
				page.total(), query.page(), query.size()));
	}

	private static String normalize(String status) {
		return status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
	}

}
