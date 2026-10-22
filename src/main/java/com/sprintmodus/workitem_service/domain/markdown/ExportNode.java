package com.sprintmodus.workitem_service.domain.markdown;

import java.util.List;

import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * One work item with everything the export writes about it, already loaded. {@code sprintName} and {@code statusName}
 * are display text ({@code null} when unknown or not in a sprint); links and comments are empty unless requested.
 */
public record ExportNode(WorkItem item, String statusName, String sprintName, List<Assignment> assignees, List<Link> links,
		List<Comment> comments, List<ExportNode> children) {

	public int count() {
		return 1 + children.stream().mapToInt(ExportNode::count).sum();
	}

}
