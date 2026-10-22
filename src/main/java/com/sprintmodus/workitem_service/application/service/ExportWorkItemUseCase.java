package com.sprintmodus.workitem_service.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.markdown.ExportNode;
import com.sprintmodus.workitem_service.domain.markdown.WorkItemMarkdownRenderer;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Exports a work item and, unless told otherwise, its whole subtree of active items as Markdown. A read: nothing is
 * audited, and the history is never part of it. Stops at {@link #MAX_ITEMS} items and says so in the document.
 */
@Service
public class ExportWorkItemUseCase {

	public static final int MAX_ITEMS = 500;

	/** What to include besides the item itself. {@code depth} is how many levels of children (null = all, 0 = none). */
	public record Options(boolean children, boolean links, boolean comments, Integer depth) {
	}

	private final WorkItemRepository items;

	private final AssignmentRepository assignments;

	private final LinkRepository links;

	private final CommentRepository comments;

	private final ProjectServiceGateway projects;

	public ExportWorkItemUseCase(WorkItemRepository items, AssignmentRepository assignments, LinkRepository links,
			CommentRepository comments, ProjectServiceGateway projects) {
		this.items = items;
		this.assignments = assignments;
		this.links = links;
		this.comments = comments;
		this.projects = projects;
	}

	public Result<String, WorkItemError> execute(UUID workItemCode, Options options) {
		WorkItem root = items.findByCode(workItemCode).orElse(null);
		if (root == null) {
			return Result.failure(new WorkItemNotFound());
		}
		Walk walk = new Walk(options);
		ExportNode tree = walk.node(root, 0);
		return Result.success(WorkItemMarkdownRenderer.render(tree, walk.truncated));
	}

	private final class Walk {

		private final Options options;

		private final Map<UUID, String> sprintNames = new HashMap<>();

		private int count;

		private boolean truncated;

		Walk(Options options) {
			this.options = options;
		}

		ExportNode node(WorkItem item, int level) {
			count++;
			List<ExportNode> children = List.of();
			boolean wantsChildren = options.children() && (options.depth() == null || level < options.depth());
			if (wantsChildren) {
				List<ExportNode> built = new java.util.ArrayList<>();
				for (WorkItem child : items.findChildren(item.code())) {
					if (count >= MAX_ITEMS) {
						truncated = true;
						break;
					}
					built.add(node(child, level + 1));
				}
				children = built;
			}
			return new ExportNode(item, item.status() == null ? null : item.status().displayName(), sprintName(item.sprintCode()),
					assignments.findByWorkItem(item.code()), options.links() ? links.findByWorkItem(item.code()) : List.of(),
					options.comments() ? comments.findByWorkItem(item.code()) : List.of(), children);
		}

		private String sprintName(UUID sprintCode) {
			if (sprintCode == null) {
				return null;
			}
			return sprintNames.computeIfAbsent(sprintCode, code -> projects.getSprint(code).fold(sprint -> sprint.name(), failure -> null));
		}

	}

}
