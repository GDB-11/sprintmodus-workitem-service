package com.sprintmodus.workitem_service.application.error;

import com.sprintmodus.common_lib.result.ApplicationError;

public sealed interface NotificationError extends ApplicationError {

	record InvalidQuery(String message) implements NotificationError {

		@Override
		public String code() {
			return "INVALID_QUERY";
		}

	}

	/** Also what someone else's notification looks like: it does not exist for the caller. */
	record NotificationNotFound() implements NotificationError {

		@Override
		public String code() {
			return "NOTIFICATION_NOT_FOUND";
		}

		@Override
		public String message() {
			return "Notification not found.";
		}

	}

}
