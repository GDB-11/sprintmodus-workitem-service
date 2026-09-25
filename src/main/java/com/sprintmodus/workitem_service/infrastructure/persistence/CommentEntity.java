package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a comment joined with its work item's code and its author. */
@Entity
@Table(name = "Comment")
class CommentEntity {

	@Id
	@Column(name = "CommentId")
	Long id;

	@Column(name = "CommentCode")
	UUID code;

	@Column(name = "WorkItemCode")
	UUID workItemCode;

	@Column(name = "AuthorCode")
	UUID authorCode;

	@Column(name = "AuthorName")
	String authorName;

	@Column(name = "Content")
	String content;

	@Column(name = "CreatedAt")
	Instant createdAt;

}
