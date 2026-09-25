package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface LinkError extends ApplicationError {

	record InvalidLink(String field, String message) implements LinkError {

		@Override
		public String code() {
			return "INVALID_LINK";
		}

	}

	record WorkItemNotFound() implements LinkError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

	record TargetNotFound() implements LinkError {

		@Override
		public String code() {
			return "TARGET_NOT_FOUND";
		}

		@Override
		public String message() {
			return "The target work item was not found.";
		}

	}

	record AlreadyLinked() implements LinkError {

		@Override
		public String code() {
			return "ALREADY_LINKED";
		}

		@Override
		public String message() {
			return "These work items are already linked this way.";
		}

	}

	/** Adding this link would close a cycle within its link type's graph (e.g. a chain of BLOCKS back on itself). */
	record CyclicLink() implements LinkError {

		@Override
		public String code() {
			return "CYCLIC_LINK";
		}

		@Override
		public String message() {
			return "This link would create a cycle.";
		}

	}

	record LinkNotFound() implements LinkError {

		@Override
		public String code() {
			return "LINK_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Link not found.";
		}

	}

}
