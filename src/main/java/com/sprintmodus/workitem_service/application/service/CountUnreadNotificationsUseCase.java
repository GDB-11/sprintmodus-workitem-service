package com.sprintmodus.workitem_service.application.service;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.NotificationError;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;

/** How many unread notifications the caller has: the number on the bell, asked for often, so it does nothing else. */
@Service
public class CountUnreadNotificationsUseCase {

	private final NotificationRepository notifications;

	public CountUnreadNotificationsUseCase(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	public Result<Integer, NotificationError> execute(Actor actor) {
		return Result.success(notifications.countUnread(actor.userCode()));
	}

}
