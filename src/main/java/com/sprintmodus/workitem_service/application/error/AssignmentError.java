package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface AssignmentError extends ApplicationError {

	record InvalidAssignment(String field, String message) implements AssignmentError {

		@Override
		public String code() {
			return "INVALID_ASSIGNMENT";
		}

	}

	record WorkItemNotFound() implements AssignmentError {

		@Override
		public String code() {
			return "WORK_ITEM_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Work item not found.";
		}

	}

	record UserNotFound() implements AssignmentError {

		@Override
		public String code() {
			return "USER_NOT_FOUND";
		}

		@Override
		public String message() {
			return "User not found.";
		}

	}

	record AssignmentNotFound() implements AssignmentError {

		@Override
		public String code() {
			return "ASSIGNMENT_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Assignment not found.";
		}

	}

	record AlreadyAssigned() implements AssignmentError {

		@Override
		public String code() {
			return "ALREADY_ASSIGNED";
		}

		@Override
		public String message() {
			return "This user already has that role on the work item.";
		}

	}

}
