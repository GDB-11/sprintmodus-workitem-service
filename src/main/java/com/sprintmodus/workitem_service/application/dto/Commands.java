package com.sprintmodus.workitem_service.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/** Inputs of the use cases. Fields a client may leave out are nullable. */
public final class Commands {

	private Commands() {
	}

	public record CreateWorkItem(Actor actor, UUID projectCode, ItemType type, String title, String description,
			String acceptanceCriteria, Priority priority, UUID parentCode, Integer effortPoints, BigDecimal estimatedHours,
			BigDecimal remainingHours) {
	}

	/** Fields left {@code null} keep their value; a blank description or acceptance criteria clears it. */
	public record UpdateWorkItem(Actor actor, UUID workItemCode, String title, String description, String acceptanceCriteria,
			Priority priority, Integer effortPoints, BigDecimal estimatedHours, BigDecimal remainingHours) {
	}

	/** {@code parentCode == null} removes the parent. */
	public record SetParent(Actor actor, UUID workItemCode, UUID parentCode) {
	}

	public record ChangeStatus(Actor actor, UUID workItemCode, String status) {
	}

	/** {@code sprintCode == null} moves the item back to the backlog. */
	public record MoveToSprint(Actor actor, UUID workItemCode, UUID sprintCode) {
	}

	/** Puts an item right before {@code beforeCode} among the items that share its rank group, or last if that is {@code null}. */
	public record ReorderWorkItem(Actor actor, UUID workItemCode, UUID beforeCode) {
	}

	public record Assign(Actor actor, UUID workItemCode, UUID userCode, AssignmentRole role) {
	}

	public record CreateLink(Actor actor, UUID workItemCode, UUID targetCode, LinkType type) {
	}

	public record AddComment(Actor actor, UUID workItemCode, String content) {
	}

	/** The search filter of the list. {@code text}, when given, is matched against title and description; {@code boardOrder}
	 * lists in the order of a board column instead of newest first. */
	public record WorkItemQuery(UUID projectCode, SprintScope sprintScope, UUID sprintCode, ItemType type, String status,
			UUID parentCode, UUID assigneeCode, Priority priority, String text, boolean boardOrder, int page, int size) {

		/** Which sprint's items are wanted. */
		public enum SprintScope {

			ANY,
			BACKLOG,
			SPRINT

		}

	}

	public record WorkflowStatusDefinition(String code, String displayName, Integer order, Boolean terminal) {
	}

	/** {@code requiredRole}, when given, is the assignment role required to make this move (parsed and validated by
	 * {@code UpsertWorkflowUseCase}); {@code null} means anyone may. */
	public record WorkflowTransitionDefinition(String from, String to, Boolean allowedBackward, String requiredRole) {

		public WorkflowTransitionDefinition(String from, String to, Boolean allowedBackward) {
			this(from, to, allowedBackward, null);
		}

	}

	/** Replaces the whole workflow of an item type. */
	public record UpsertWorkflow(Actor actor, ItemType itemType, List<WorkflowStatusDefinition> statuses,
			List<WorkflowTransitionDefinition> transitions) {
	}

}
