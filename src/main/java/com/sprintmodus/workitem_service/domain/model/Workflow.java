package com.sprintmodus.workitem_service.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The statuses and legal moves of one item type, as configured for the tenant.
 * <p>
 * A transition {@code A -> B} allows moving forward from A to B; when it is marked {@code allowedBackward} the reverse
 * move B to A is allowed too. A move is only possible into an active status.
 */
public record Workflow(ItemType itemType, List<WorkflowStatus> statuses, List<WorkflowTransition> transitions) {

	public record WorkflowStatus(String code, String displayName, int order, boolean terminal, boolean active) {
	}

	/** {@code requiredRole}, when set, restricts this move to someone assigned to the item in that role (an organization
	 * OWNER or ADMIN may always override it); {@code null} means anyone may make the move. */
	public record WorkflowTransition(String from, String to, boolean allowedBackward, AssignmentRole requiredRole) {

		public WorkflowTransition(String from, String to, boolean allowedBackward) {
			this(from, to, allowedBackward, null);
		}

	}

	/** The status a new item of this type starts in: the active one with the lowest order. */
	public Optional<WorkflowStatus> initialStatus() {
		return statuses.stream().filter(WorkflowStatus::active).min(Comparator.comparingInt(WorkflowStatus::order));
	}

	/** A status as items and clients see it, knowing whether it is this workflow's initial one. */
	public StatusRef ref(WorkflowStatus status) {
		boolean initial = initialStatus().filter(first -> first.code().equals(status.code())).isPresent();
		return new StatusRef(status.code(), status.displayName(), initial, status.terminal());
	}

	public Optional<WorkflowStatus> status(String code) {
		return statuses.stream().filter(status -> status.code().equals(code)).findFirst();
	}

	/** Whether an item may move from one status to another. */
	public boolean canMove(String from, String to) {
		if (from.equals(to) || status(to).filter(WorkflowStatus::active).isEmpty()) {
			return false;
		}
		return transitionFor(from, to).isPresent();
	}

	/** The transition that would carry the move, forward or via an allowed backward move; empty if none does. */
	public Optional<WorkflowTransition> transitionFor(String from, String to) {
		return transitions.stream().filter(transition -> (transition.from().equals(from) && transition.to().equals(to))
				|| (transition.allowedBackward() && transition.from().equals(to) && transition.to().equals(from))).findFirst();
	}

	/** The statuses an item in {@code from} may move to, in workflow order. */
	public List<WorkflowStatus> targetsFrom(String from) {
		return statuses.stream().filter(status -> canMove(from, status.code())).sorted(Comparator.comparingInt(WorkflowStatus::order))
				.toList();
	}

}
