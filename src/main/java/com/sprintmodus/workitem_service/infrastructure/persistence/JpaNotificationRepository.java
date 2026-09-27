package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;
import com.sprintmodus.workitem_service.domain.model.Notification;
import com.sprintmodus.workitem_service.domain.model.NotificationType;
import com.sprintmodus.workitem_service.domain.model.UserRef;

@Repository
class JpaNotificationRepository implements NotificationRepository {

	private final NotificationQueries notifications;

	JpaNotificationRepository(NotificationQueries notifications) {
		this.notifications = notifications;
	}

	@Override
	public Optional<Notification> create(NotificationType type, UUID recipientCode, UUID workItemCode, UUID commentCode, UUID actorCode) {
		String code = UUID.randomUUID().toString();
		int inserted = notifications.insert(code, recipientCode.toString(), type.name(), workItemCode.toString(),
				commentCode == null ? "" : commentCode.toString(), actorCode.toString());
		if (inserted == 0) {
			return Optional.empty();
		}
		return notifications.find(recipientCode.toString(), code).map(JpaNotificationRepository::toDomain);
	}

	@Override
	public Page list(UUID recipientCode, boolean unreadOnly, int page, int size) {
		String recipient = recipientCode.toString();
		List<Notification> items = notifications.list(recipient, unreadOnly, size, (long) page * size).stream().map(JpaNotificationRepository::toDomain)
				.toList();
		return new Page(items, notifications.count(recipient, unreadOnly));
	}

	@Override
	public int countUnread(UUID recipientCode) {
		return Math.toIntExact(notifications.count(recipientCode.toString(), true));
	}

	@Override
	public boolean markRead(UUID recipientCode, UUID notificationCode) {
		notifications.markRead(recipientCode.toString(), notificationCode.toString());
		// an update that changes nothing reports 0 rows, whether it was already read or not there at all
		return notifications.find(recipientCode.toString(), notificationCode.toString()).isPresent();
	}

	@Override
	public int markAllRead(UUID recipientCode) {
		return notifications.markAllRead(recipientCode.toString());
	}

	private static Notification toDomain(NotificationEntity entity) {
		return new Notification(entity.code, NotificationType.valueOf(entity.type), entity.workItemCode, entity.workItemKey, entity.workItemTitle,
				entity.commentCode, new UserRef(entity.actorCode, entity.actorName), entity.createdAt, entity.readAt);
	}

}
