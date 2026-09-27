package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface UserSearchError extends ApplicationError {

	record InvalidQuery(String message) implements UserSearchError {

		@Override
		public String code() {
			return "INVALID_QUERY";
		}

	}

}
