package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

/** Why a work item could not be moved to another sprint. */
public sealed interface SprintMoveError extends ApplicationError {

	record WorkItemNotFound() implements SprintMoveError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

	/** Only organization owners and admins plan work into sprints. */
	record NotAllowed() implements SprintMoveError {

		@Override
		public String code() {
			return "FORBIDDEN";
		}

		@Override
		public String message() {
			return "Only an owner or an admin can move work items between sprints.";
		}

	}

	record SprintNotFound() implements SprintMoveError {

		@Override
		public String code() {
			return "SPRINT_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Sprint not found.";
		}

	}

	record SprintClosed() implements SprintMoveError {

		@Override
		public String code() {
			return "SPRINT_CLOSED";
		}

		@Override
		public String message() {
			return "A closed sprint cannot receive work items.";
		}

	}

	/** The sprint belongs to another project than the work item. */
	record SprintOfAnotherProject() implements SprintMoveError {

		@Override
		public String code() {
			return "SPRINT_OF_ANOTHER_PROJECT";
		}

		@Override
		public String message() {
			return "The sprint belongs to a different project than the work item.";
		}

	}

	/** project-service could not be reached, so the sprint could not be checked. */
	record ProjectServiceUnavailable() implements SprintMoveError {

		@Override
		public String code() {
			return "PROJECT_SERVICE_UNAVAILABLE";
		}

		@Override
		public String message() {
			return "Sprints cannot be checked right now. Please try again.";
		}

	}

}
