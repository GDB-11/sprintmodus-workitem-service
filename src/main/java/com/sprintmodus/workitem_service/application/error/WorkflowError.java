package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface WorkflowError extends ApplicationError {

	record InvalidWorkflow(String field, String message) implements WorkflowError {

		@Override
		public String code() {
			return "INVALID_WORKFLOW";
		}

	}

	record NotAllowed() implements WorkflowError {

		@Override
		public String code() {
			return "FORBIDDEN";
		}

		@Override
		public String message() {
			return "You do not have permission to do this.";
		}

	}

	/** A status the new workflow drops still has work items in it. */
	record StatusInUse(String status, int items) implements WorkflowError {

		@Override
		public String code() {
			return "STATUS_IN_USE";
		}

		@Override
		public String message() {
			return "Status " + status + " still has " + items + (items == 1 ? " work item" : " work items")
					+ ". Move them to another status first.";
		}

	}

}
