package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.service.CountUnreadNotificationsUseCase;
import com.sprintmodus.workitem_service.application.service.ListNotificationsUseCase;
import com.sprintmodus.workitem_service.application.service.MarkAllNotificationsReadUseCase;
import com.sprintmodus.workitem_service.application.service.MarkNotificationReadUseCase;

/** Everything is scoped to the caller: there is no way to ask for another user's notifications. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

	private final ListNotificationsUseCase list;

	private final CountUnreadNotificationsUseCase countUnread;

	private final MarkNotificationReadUseCase markRead;

	private final MarkAllNotificationsReadUseCase markAllRead;

	NotificationController(ListNotificationsUseCase list, CountUnreadNotificationsUseCase countUnread, MarkNotificationReadUseCase markRead,
			MarkAllNotificationsReadUseCase markAllRead) {
		this.list = list;
		this.countUnread = countUnread;
		this.markRead = markRead;
		this.markAllRead = markAllRead;
	}

	@GetMapping
	ResponseEntity<?> list(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "false") boolean unread,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return list.execute(Actors.from(user), unread, page, size).fold(found -> ResponseEntity.ok(Responses.NotificationList.from(found)),
				ErrorMapper::toResponse);
	}

	@GetMapping("/unread-count")
	ResponseEntity<?> unreadCount(@AuthenticationPrincipal AuthenticatedUser user) {
		return countUnread.execute(Actors.from(user)).fold(count -> ResponseEntity.ok(new Responses.UnreadCount(count)), ErrorMapper::toResponse);
	}

	@PostMapping("/{notificationCode}/read")
	ResponseEntity<?> read(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID notificationCode) {
		return markRead.execute(Actors.from(user), notificationCode).fold(done -> ResponseEntity.noContent().build(), ErrorMapper::toResponse);
	}

	@PostMapping("/read-all")
	ResponseEntity<?> readAll(@AuthenticationPrincipal AuthenticatedUser user) {
		return markAllRead.execute(Actors.from(user)).fold(marked -> ResponseEntity.noContent().build(), ErrorMapper::toResponse);
	}

}
