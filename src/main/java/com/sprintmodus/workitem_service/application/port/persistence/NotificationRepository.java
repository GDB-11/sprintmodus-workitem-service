package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.Notification;
import com.sprintmodus.workitem_service.domain.model.NotificationType;

/**
 * Notifications, always addressed to and read by their recipient: every read and write takes the recipient, so someone
 * else's notification is simply not found. A notification about a work item that was soft-deleted is not listed or counted
 * (its link would lead nowhere).
 */
public interface NotificationRepository {

	/** One page of notifications, newest first, and how many match in all. */
	record Page(List<Notification> items, long total) {
	}

	/**
	 * Writes a notification. Empty if the recipient, the actor, the item or the comment does not exist. Nobody is notified
	 * twice of one type for one comment: the database refuses the second (which the use case never asks for).
	 */
	Optional<Notification> create(NotificationType type, UUID recipientCode, UUID workItemCode, UUID commentCode, UUID actorCode);

	Page list(UUID recipientCode, boolean unreadOnly, int page, int size);

	int countUnread(UUID recipientCode);

	/** Marks one notification as read; already read stays as it was. {@code false} if the recipient has no such notification. */
	boolean markRead(UUID recipientCode, UUID notificationCode);

	/** Marks every unread notification of the recipient as read and says how many it was. */
	int markAllRead(UUID recipientCode);

}
