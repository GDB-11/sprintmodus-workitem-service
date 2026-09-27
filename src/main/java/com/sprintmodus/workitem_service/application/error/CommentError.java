package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface CommentError extends ApplicationError {

	record InvalidComment(String message) implements CommentError {

		@Override
		public String code() {
			return "INVALID_COMMENT";
		}

	}

	/** A mention names someone who is not an active user of the tenant (a stale autocomplete, or a made-up code). */
	record UnknownMention(String name) implements CommentError {

		@Override
		public String code() {
			return "UNKNOWN_MENTION";
		}

		@Override
		public String message() {
			return "The user @" + name + " does not exist or is no longer active.";
		}

	}

	record WorkItemNotFound() implements CommentError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

}
