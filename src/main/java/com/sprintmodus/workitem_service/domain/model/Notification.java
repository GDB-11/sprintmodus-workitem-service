package com.sprintmodus.workitem_service.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Something a user should know about. Presentation is the client's: it builds the sentence from {@code type}, the
 * {@code actor} and the work item's key and title. {@code commentCode} is set when it is about a comment; {@code readAt} is
 * {@code null} while it is unread.
 */
public record Notification(UUID code, NotificationType type, UUID workItemCode, String workItemKey, String workItemTitle,
		UUID commentCode, UserRef actor, Instant createdAt, Instant readAt) {

	public boolean read() {
		return readAt != null;
	}

}
