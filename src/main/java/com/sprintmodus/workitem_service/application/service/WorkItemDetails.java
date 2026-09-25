package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/** Assembles the detail view of a work item: the item, its children, assignees and links, and where it may move next. */
@Component
public class WorkItemDetails {

	private final WorkItemRepository items;

	private final AssignmentRepository assignments;

	private final LinkRepository links;

	private final WorkflowRepository workflows;

	public WorkItemDetails(WorkItemRepository items, AssignmentRepository assignments, LinkRepository links,
			WorkflowRepository workflows) {
		this.items = items;
		this.assignments = assignments;
		this.links = links;
		this.workflows = workflows;
	}

	/** Empty if the item does not exist (or was deleted). */
	public Optional<WorkItemResponse> load(UUID code, List<Warning> warnings) {
		return items.findByCode(code).map(item -> assemble(item, warnings));
	}

	public WorkItemResponse assemble(WorkItem item, List<Warning> warnings) {
		return WorkItemResponse.from(item, assignments.findByWorkItem(item.code()), items.findChildren(item.code()),
				links.findByWorkItem(item.code()), workflows.find(item.type()), warnings);
	}

}
