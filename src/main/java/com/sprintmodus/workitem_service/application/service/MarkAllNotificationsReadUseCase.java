package com.sprintmodus.workitem_service.application.service;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.NotificationError;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;

/** Marks every unread notification of the caller as read and says how many it was. */
@Service
public class MarkAllNotificationsReadUseCase {

	private final NotificationRepository notifications;

	private final TransactionRunner transactions;

	public MarkAllNotificationsReadUseCase(NotificationRepository notifications, TransactionRunner transactions) {
		this.notifications = notifications;
		this.transactions = transactions;
	}

	public Result<Integer, NotificationError> execute(Actor actor) {
		return Result.success(transactions.inTransaction(() -> notifications.markAllRead(actor.userCode())));
	}

}
