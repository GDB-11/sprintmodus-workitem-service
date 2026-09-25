package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;

/** Why a status change was refused. */
public sealed interface StatusTransitionError extends ApplicationError {

	record WorkItemNotFound() implements StatusTransitionError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

	/** The target status does not exist (or is retired) for the item's type. */
	record UnknownStatus(String status) implements StatusTransitionError {

		@Override
		public String code() {
			return "UNKNOWN_STATUS";
		}

		@Override
		public String message() {
			return "There is no status '" + status + "' for this work item type.";
		}

	}

	/** The workflow does not allow this move. */
	record InvalidTransition(String from, String to) implements StatusTransitionError {

		@Override
		public String code() {
			return "INVALID_TRANSITION";
		}

		@Override
		public String message() {
			return "Cannot move from " + from + " to " + to + ".";
		}

	}

	/** The transition requires an assignment role the actor does not hold (and they are not an OWNER/ADMIN). */
	record NotAllowed(AssignmentRole requiredRole) implements StatusTransitionError {

		@Override
		public String code() {
			return "FORBIDDEN";
		}

		@Override
		public String message() {
			return "Only a " + requiredRole + " assigned to this work item, or an organization owner or admin, may make this move.";
		}

	}

	/** Someone else changed the status between reading the item and updating it. */
	record StatusChangedConcurrently() implements StatusTransitionError {

		@Override
		public String code() {
			return "STATUS_CHANGED_CONCURRENTLY";
		}

		@Override
		public String message() {
			return "The work item was changed by someone else. Reload it and try again.";
		}

	}

}
