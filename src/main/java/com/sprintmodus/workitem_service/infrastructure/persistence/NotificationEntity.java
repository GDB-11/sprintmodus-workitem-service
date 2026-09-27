package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a notification joined with its work item (code, display key, title), its comment and its actor. */
@Entity
@Table(name = "Notification")
class NotificationEntity {

	@Id
	@Column(name = "NotificationId")
	Long id;

	@Column(name = "NotificationCode")
	UUID code;

	@Column(name = "Type")
	String type;

	@Column(name = "WorkItemCode")
	UUID workItemCode;

	@Column(name = "WorkItemKey")
	String workItemKey;

	@Column(name = "WorkItemTitle")
	String workItemTitle;

	@Column(name = "CommentCode")
	UUID commentCode;

	@Column(name = "ActorCode")
	UUID actorCode;

	@Column(name = "ActorName")
	String actorName;

	@Column(name = "CreatedAt")
	Instant createdAt;

	@Column(name = "ReadAt")
	Instant readAt;

}
