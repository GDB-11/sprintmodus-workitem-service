package com.sprintmodus.workitem_service.adapter.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.application.dto.Responses.AssigneeResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.ChildResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.CommentResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.LinkResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemPage;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemSummary;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkflowResponse;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.StatusRef;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.Warning;

/**
 * Bodies of the REST responses, named as clients expect: {@code workItemCode}, {@code projectCode}, {@code sprintCode},
 * {@code isTerminal}. Internal database ids are never part of them.
 */
public final class Responses {

	private Responses() {
	}

	public record Status(String code, String displayName, boolean isInitial, boolean isTerminal) {

		public static Status from(StatusRef status) {
			return new Status(status.code(), status.displayName(), status.initial(), status.terminal());
		}

	}

	public record User(UUID userCode, String fullName) {

		public static User from(UserRef user) {
			return user == null ? null : new User(user.userCode(), user.fullName());
		}

	}

	public record Warn(String code, String message) {

		static Warn from(Warning warning) {
			return new Warn(warning.code(), warning.message());
		}

	}

	public record Assignee(UUID assignmentCode, UUID userCode, String fullName, AssignmentRole role) {

		public static Assignee from(AssigneeResponse response) {
			return new Assignee(response.code(), response.userCode(), response.fullName(), response.role());
		}

	}

	public record Child(UUID workItemCode, long workItemNumber, String displayKey, ItemType type, String title, Status status) {

		static Child from(ChildResponse response) {
			return new Child(response.code(), response.number(), response.displayKey(), response.type(), response.title(),
					Status.from(response.status()));
		}

	}

	/** A link as one work item's detail page lists it: {@code item} is the work item at the far end. */
	public record Link(UUID linkCode, LinkType type, Child item) {

		public static Link from(LinkResponse response) {
			return new Link(response.code(), response.type(), Child.from(response.item()));
		}

	}

	/** A row of a list or a card on a board. */
	public record Summary(UUID workItemCode, long workItemNumber, String displayKey, UUID projectCode, ItemType type, String title,
			Priority priority, Status status, UUID sprintCode, UUID parentCode, int effortPoints, User createdBy, Instant updatedAt,
			Integer boardRank, int childCount, List<Assignee> assignees) {

		static Summary from(WorkItemSummary summary) {
			return new Summary(summary.code(), summary.number(), summary.displayKey(), summary.projectCode(), summary.type(),
					summary.title(), summary.priority(), Status.from(summary.status()), summary.sprintCode(), summary.parentCode(),
					summary.effortPoints(), User.from(summary.createdBy()), summary.updatedAt(), summary.boardRank(), summary.childCount(),
					summary.assignees().stream().map(Assignee::from).toList());
		}

	}

	public record Page(List<Summary> items, long total, int page, int size) {

		public static Page from(WorkItemPage page) {
			return new Page(page.items().stream().map(Summary::from).toList(), page.total(), page.page(), page.size());
		}

	}

	/** The detail of a work item; {@code allowedStatuses} are where it can move now, {@code warnings} notes about what was just done. */
	public record WorkItem(UUID workItemCode, long workItemNumber, String displayKey, UUID projectCode, String projectKey, ItemType type,
			String title, String description, String acceptanceCriteria, Priority priority, Status status, UUID sprintCode, UUID parentCode,
			int effortPoints, int childrenEffortPoints, BigDecimal estimatedHours, BigDecimal remainingHours, User createdBy,
			User updatedBy, Instant createdAt, Instant updatedAt, List<Assignee> assignees, List<Child> children, List<Link> links,
			List<Status> allowedStatuses, List<Warn> warnings) {

		public static WorkItem from(WorkItemResponse item) {
			return new WorkItem(item.code(), item.number(), item.displayKey(), item.projectCode(), item.projectKey(), item.type(),
					item.title(), item.description(), item.acceptanceCriteria(), item.priority(), Status.from(item.status()),
					item.sprintCode(), item.parentCode(), item.effortPoints(), item.childrenEffortPoints(), item.estimatedHours(),
					item.remainingHours(), User.from(item.createdBy()), User.from(item.updatedBy()), item.createdAt(), item.updatedAt(),
					item.assignees().stream().map(Assignee::from).toList(), item.children().stream().map(Child::from).toList(),
					item.links().stream().map(Link::from).toList(),
					item.allowedStatuses().stream().map(Status::from).toList(), item.warnings().stream().map(Warn::from).toList());
		}

	}

	public record Comment(UUID commentCode, UUID workItemCode, User author, String content, Instant createdAt) {

		public static Comment from(CommentResponse response) {
			return new Comment(response.code(), response.workItemCode(), User.from(response.author()), response.content(), response.createdAt());
		}

	}

	public record Workflow(ItemType itemType, List<WorkflowStatus> statuses, List<WorkflowTransition> transitions) {

		public record WorkflowStatus(String code, String displayName, int order, boolean isTerminal) {
		}

		public record WorkflowTransition(String from, String to, boolean allowedBackward, AssignmentRole requiredRole) {
		}

		public static Workflow from(WorkflowResponse response) {
			return new Workflow(response.itemType(),
					response.statuses().stream().map(s -> new WorkflowStatus(s.code(), s.displayName(), s.order(), s.terminal())).toList(),
					response.transitions().stream()
							.map(t -> new WorkflowTransition(t.from(), t.to(), t.allowedBackward(), t.requiredRole())).toList());
		}

	}

}
