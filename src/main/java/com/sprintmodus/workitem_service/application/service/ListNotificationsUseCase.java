package com.sprintmodus.workitem_service.application.service;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Responses.NotificationPage;
import com.sprintmodus.workitem_service.application.dto.Responses.NotificationResponse;
import com.sprintmodus.workitem_service.application.error.NotificationError;
import com.sprintmodus.workitem_service.application.error.NotificationError.InvalidQuery;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;

/** The caller's own notifications, newest first, a page at a time. It is a read: it does not mark anything as read. */
@Service
public class ListNotificationsUseCase {

	public static final int MAX_PAGE_SIZE = 100;

	private final NotificationRepository notifications;

	public ListNotificationsUseCase(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	public Result<NotificationPage, NotificationError> execute(Actor actor, boolean unreadOnly, int page, int size) {
		if (page < 0) {
			return Result.failure(new InvalidQuery("The page number cannot be negative."));
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			return Result.failure(new InvalidQuery("The page size must be between 1 and " + MAX_PAGE_SIZE + "."));
		}
		NotificationRepository.Page found = notifications.list(actor.userCode(), unreadOnly, page, size);
		return Result.success(new NotificationPage(found.items().stream().map(NotificationResponse::from).toList(), found.total(), page, size));
	}

}
