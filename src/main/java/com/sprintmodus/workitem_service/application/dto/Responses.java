package com.sprintmodus.workitem_service.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.StatusRef;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.model.Workflow;

/** Outputs of the use cases. */
public final class Responses {

	private Responses() {
	}

	public record AssigneeResponse(UUID code, UUID userCode, String fullName, AssignmentRole role) {

		public static AssigneeResponse from(Assignment assignment) {
			return new AssigneeResponse(assignment.code(), assignment.userCode(), assignment.fullName(), assignment.role());
		}

	}

	/** A child of a work item, as its parent lists it. */
	public record ChildResponse(UUID code, long number, String displayKey, ItemType type, String title, StatusRef status) {

		public static ChildResponse from(WorkItem child) {
			return new ChildResponse(child.code(), child.number(), child.displayKey(), child.type(), child.title(), child.status());
		}

	}

	/**
	 * A work item in a list: what a row or a board card needs, including who works on it and how many children it has (the
	 * children themselves are listed with {@code parentCode}). {@code boardRank} is its manual place in its column, or
	 * {@code null} if it was never ranked.
	 */
	public record WorkItemSummary(UUID code, long number, String displayKey, UUID projectCode, ItemType type, String title,
			Priority priority, StatusRef status, UUID sprintCode, UUID parentCode, int effortPoints, UserRef createdBy,
			Instant updatedAt, Integer boardRank, int childCount, List<AssigneeResponse> assignees) {

		public static WorkItemSummary from(WorkItem item, List<Assignment> assignees) {
			return new WorkItemSummary(item.code(), item.number(), item.displayKey(), item.projectCode(), item.type(), item.title(),
					item.priority(), item.status(), item.sprintCode(), item.parentCode(), item.effortPoints(), item.createdBy(),
					item.updatedAt(), item.boardRank(), item.childCount(), assignees.stream().map(AssigneeResponse::from).toList());
		}

	}

	public record WorkItemPage(List<WorkItemSummary> items, long total, int page, int size) {
	}

	/** A link as one work item's detail page lists it. */
	public record LinkResponse(UUID code, LinkType type, ChildResponse item) {

		public static LinkResponse from(Link link) {
			return new LinkResponse(link.code(), link.type(), ChildResponse.from(link.other()));
		}

	}

	/**
	 * A work item with everything its detail page shows. {@code childrenEffortPoints} is the sum of its active Task
	 * children's own effort points (0 if it has none) — shown next to {@code effortPoints} for reference, never replacing
	 * it: a PBI/Bug's own estimate stays whatever was entered for it. {@code allowedStatuses} are the statuses it can move
	 * to right now, so a client never has to know the workflow rules; {@code warnings} are non-blocking notes about the
	 * operation just done.
	 */
	public record WorkItemResponse(UUID code, long number, String displayKey, UUID projectCode, String projectKey, ItemType type,
			String title, String description, String acceptanceCriteria, Priority priority, StatusRef status, UUID sprintCode,
			UUID parentCode, int effortPoints, int childrenEffortPoints, BigDecimal estimatedHours, BigDecimal remainingHours,
			UserRef createdBy, UserRef updatedBy, Instant createdAt, Instant updatedAt, List<AssigneeResponse> assignees,
			List<ChildResponse> children, List<LinkResponse> links, List<StatusRef> allowedStatuses, List<Warning> warnings) {

		public static WorkItemResponse from(WorkItem item, List<Assignment> assignments, List<WorkItem> children, List<Link> links,
				Workflow workflow, List<Warning> warnings) {
			int childrenEffort = children.stream().filter(child -> child.type() == ItemType.TASK).mapToInt(WorkItem::effortPoints).sum();
			return new WorkItemResponse(item.code(), item.number(), item.displayKey(), item.projectCode(), item.projectKey(),
					item.type(), item.title(), item.description(), item.acceptanceCriteria(), item.priority(), item.status(),
					item.sprintCode(), item.parentCode(), item.effortPoints(), childrenEffort, item.estimatedHours(),
					item.remainingHours(), item.createdBy(), item.updatedBy(), item.createdAt(), item.updatedAt(),
					assignments.stream().map(AssigneeResponse::from).toList(), children.stream().map(ChildResponse::from).toList(),
					links.stream().map(LinkResponse::from).toList(),
					workflow.targetsFrom(item.status().code()).stream().map(workflow::ref).toList(), warnings);
		}

	}

	public record CommentResponse(UUID code, UUID workItemCode, UserRef author, String content, Instant createdAt) {

		public static CommentResponse from(Comment comment) {
			return new CommentResponse(comment.code(), comment.workItemCode(), comment.author(), comment.content(), comment.createdAt());
		}

	}

	public record WorkflowResponse(ItemType itemType, List<Workflow.WorkflowStatus> statuses, List<Workflow.WorkflowTransition> transitions) {

		/** The active statuses only: retired ones are history a client should not offer. */
		public static WorkflowResponse from(Workflow workflow) {
			return new WorkflowResponse(workflow.itemType(),
					workflow.statuses().stream().filter(Workflow.WorkflowStatus::active)
							.sorted(java.util.Comparator.comparingInt(Workflow.WorkflowStatus::order)).toList(),
					workflow.transitions());
		}

	}

}
