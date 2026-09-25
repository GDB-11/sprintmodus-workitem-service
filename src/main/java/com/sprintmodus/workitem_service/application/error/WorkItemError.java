package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

/** Why a work item operation was refused. */
public sealed interface WorkItemError extends ApplicationError {

	/** A field is missing or malformed; {@code message} says what to fix. */
	record InvalidWorkItemData(String field, String message) implements WorkItemError {

		@Override
		public String code() {
			return "INVALID_WORK_ITEM_DATA";
		}

	}

	record WorkItemNotFound() implements WorkItemError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

	record ProjectNotFound() implements WorkItemError {

		@Override
		public String code() {
			return "PROJECT_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Project not found.";
		}

	}

	/** The requested parent cannot be used (itself, a descendant, or in another project); {@code message} says why. */
	record InvalidParent(String message) implements WorkItemError {

		@Override
		public String code() {
			return "INVALID_PARENT";
		}

	}

	record HasChildren() implements WorkItemError {

		@Override
		public String code() {
			return "WORK_ITEM_HAS_CHILDREN";
		}

		@Override
		public String message() {
			return "This work item has children. Delete or move them first.";
		}

	}

	/** The item's type has no active status, so a new item has nowhere to start. */
	record WorkflowNotConfigured() implements WorkItemError {

		@Override
		public String code() {
			return "WORKFLOW_NOT_CONFIGURED";
		}

		@Override
		public String message() {
			return "No workflow is configured for this work item type.";
		}

	}

	record NotAllowed() implements WorkItemError {

		@Override
		public String code() {
			return "FORBIDDEN";
		}

		@Override
		public String message() {
			return "You do not have permission to do this.";
		}

	}

}
