package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface CommentError extends ApplicationError {

	record InvalidComment(String message) implements CommentError {

		@Override
		public String code() {
			return "INVALID_COMMENT";
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
