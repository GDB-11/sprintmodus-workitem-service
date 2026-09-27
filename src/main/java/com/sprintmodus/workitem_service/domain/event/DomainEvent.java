package com.sprintmodus.workitem_service.domain.event;

import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.NotificationType;

/**
 * Something that happened to a work item and is already committed. These are facts in the past tense, published after the
 * transaction so a listener never sees a change that rolled back (the tenant and the moment are added by the publisher, see
 * {@code TenantEvent}). They carry codes and machine identifiers only (no display text, no internal ids): whoever consumes
 * them looks up the rest by {@code workItemCode}.
 */
public sealed interface DomainEvent {

	UUID workItemCode();

	/** The user who made the change. */
	UUID actorCode();

	/** {@code fromStatus}/{@code toStatus} are {@code StatusCode}s, e.g. {@code IN_PROGRESS}. */
	record WorkItemStatusChanged(UUID workItemCode, UUID actorCode, String fromStatus, String toStatus)
			implements DomainEvent {
	}

	record WorkItemAssigned(UUID workItemCode, UUID actorCode, UUID assignmentCode, UUID userCode,
			AssignmentRole role) implements DomainEvent {
	}

	record WorkItemUnassigned(UUID workItemCode, UUID actorCode, UUID assignmentCode, UUID userCode,
			AssignmentRole role) implements DomainEvent {
	}

	/** The comment's text is not part of the event: it can be long and a consumer that needs it reads it by {@code commentCode}. */
	record WorkItemCommentAdded(UUID workItemCode, UUID actorCode, UUID commentCode) implements DomainEvent {
	}

	/** {@code userCode} was mentioned in a comment by {@code actorCode}; never published for a mention of oneself. */
	record UserMentioned(UUID workItemCode, UUID actorCode, UUID commentCode, UUID userCode) implements DomainEvent {
	}

	/**
	 * A notification row was written for {@code userCode}. <b>This is the seam for e-mail (and any other channel):</b> a later
	 * {@code @Async @EventListener} of {@code TenantEvent} looks the notification up by its code and sends it, honouring the
	 * user's preferences. Nothing listens today, and the event carries no text.
	 */
	record NotificationCreated(UUID workItemCode, UUID actorCode, UUID notificationCode, UUID userCode, NotificationType type)
			implements DomainEvent {
	}

}
