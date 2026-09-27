package com.sprintmodus.workitem_service.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.NotificationError;
import com.sprintmodus.workitem_service.application.error.NotificationError.NotificationNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;

/**
 * Marks one of the caller's notifications as read. Idempotent (reading a read one succeeds and keeps its first read time),
 * and someone else's notification is a not-found, exactly like one that does not exist. Reading is not a change of the
 * work item, so it writes no audit entry.
 */
@Service
public class MarkNotificationReadUseCase {

	private final NotificationRepository notifications;

	private final TransactionRunner transactions;

	public MarkNotificationReadUseCase(NotificationRepository notifications, TransactionRunner transactions) {
		this.notifications = notifications;
		this.transactions = transactions;
	}

	public Result<Unit, NotificationError> execute(Actor actor, UUID notificationCode) {
		boolean found = transactions.inTransaction(() -> notifications.markRead(actor.userCode(), notificationCode));
		return found ? Result.success(Unit.VALUE) : Result.failure(new NotificationNotFound());
	}

}
