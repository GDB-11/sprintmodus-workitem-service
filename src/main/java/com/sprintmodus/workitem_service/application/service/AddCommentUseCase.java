package com.sprintmodus.workitem_service.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.dto.Responses.CommentResponse;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.CommentError.InvalidComment;
import com.sprintmodus.workitem_service.application.error.CommentError.UnknownMention;
import com.sprintmodus.workitem_service.application.error.CommentError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.DomainEventPublisher;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;
import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.NotificationCreated;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.UserMentioned;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.WorkItemCommentAdded;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.Notification;
import com.sprintmodus.workitem_service.domain.model.NotificationType;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.service.MentionParser;
import com.sprintmodus.workitem_service.domain.service.MentionParser.Mention;

/**
 * Adds a comment to a work item and, in the same transaction, records {@code COMMENTED} (with the first 100 characters, where
 * a mention reads {@code @Full Name}), who it mentions and one {@code MENTIONED} notification for every mentioned user other
 * than its author, so a saved comment can never lose its notifications. A mention of a user who does not exist or is inactive
 * is refused (nothing is written): a stale autocomplete must not store a mention that notifies nobody. Once committed it
 * publishes {@link WorkItemCommentAdded} and, per notification, {@link UserMentioned} and {@link NotificationCreated}: the
 * seam where e-mail plugs in later. Comments are not edited or deleted yet.
 */
@Service
public class AddCommentUseCase {

	static final int MAX_COMMENT_LENGTH = 10_000;

	private static final int AUDIT_PREVIEW_LENGTH = 100;

	private final CommentRepository comments;

	private final UserRepository users;

	private final NotificationRepository notifications;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	private final DomainEventPublisher events;

	public AddCommentUseCase(CommentRepository comments, UserRepository users, NotificationRepository notifications, AuditRepository audit,
			TransactionRunner transactions, DomainEventPublisher events) {
		this.comments = comments;
		this.users = users;
		this.notifications = notifications;
		this.audit = audit;
		this.transactions = transactions;
		this.events = events;
	}

	/** What the transaction produced, so the events can be published once it has committed. */
	private record Saved(CommentResponse comment, List<Sent> sent) {
	}

	private record Sent(UUID recipient, Notification notification) {
	}

	public Result<CommentResponse, CommentError> execute(AddComment command) {
		String content = command.content() == null ? "" : command.content().trim();
		if (content.isEmpty() || content.length() > MAX_COMMENT_LENGTH) {
			return Result.failure(new InvalidComment("Write a comment of up to " + MAX_COMMENT_LENGTH + " characters."));
		}
		List<Mention> mentions = MentionParser.parse(content);
		if (mentions.size() > MentionParser.MAX_DISTINCT_MENTIONS) {
			return Result.failure(new InvalidComment("Mention at most " + MentionParser.MAX_DISTINCT_MENTIONS + " people in one comment."));
		}
		UUID author = command.actor().userCode();
		Result<Saved, CommentError> result = transactions.inTransaction(() -> {
			Set<UUID> known = users.findActive(mentions.stream().map(Mention::userCode).toList()).stream().map(UserRef::userCode)
					.collect(Collectors.toSet());
			for (Mention mention : mentions) {
				if (!known.contains(mention.userCode())) {
					return Result.<Saved, CommentError>failure(new UnknownMention(mention.name()));
				}
			}
			Comment comment = comments.add(command.workItemCode(), author, content).orElse(null);
			if (comment == null) {
				return Result.<Saved, CommentError>failure(new WorkItemNotFound());
			}
			List<UUID> mentioned = mentions.stream().map(Mention::userCode).toList();
			if (!mentioned.isEmpty()) {
				comments.addMentions(comment.code(), mentioned);
			}
			List<Sent> sent = new ArrayList<>();
			for (UUID userCode : mentioned) {
				// a self-mention stays in the text and in CommentMention, but notifies nobody; the mentions are already distinct
				if (!userCode.equals(author)) {
					notifications.create(NotificationType.MENTIONED, userCode, command.workItemCode(), comment.code(), author)
							.ifPresent(notification -> sent.add(new Sent(userCode, notification)));
				}
			}
			String readable = MentionParser.plainText(content);
			String preview = readable.length() <= AUDIT_PREVIEW_LENGTH ? readable : readable.substring(0, AUDIT_PREVIEW_LENGTH);
			audit.append(new AuditEntry(command.workItemCode(), author, ChangeType.COMMENTED, "Comment", null, preview,
					Map.of("commentCode", comment.code().toString())));
			return Result.<Saved, CommentError>success(new Saved(CommentResponse.from(comment), sent));
		});
		return result.tap(saved -> publish(command, saved)).map(Saved::comment);
	}

	private void publish(AddComment command, Saved saved) {
		UUID author = command.actor().userCode();
		events.publish(new WorkItemCommentAdded(command.workItemCode(), author, saved.comment().code()));
		for (Sent sent : saved.sent()) {
			events.publish(new UserMentioned(command.workItemCode(), author, saved.comment().code(), sent.recipient()));
			events.publish(new NotificationCreated(command.workItemCode(), author, sent.notification().code(), sent.recipient(),
					sent.notification().type()));
		}
	}

}
