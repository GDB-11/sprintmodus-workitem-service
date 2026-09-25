package com.sprintmodus.workitem_service.application.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.dto.Responses.CommentResponse;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.CommentError.InvalidComment;
import com.sprintmodus.workitem_service.application.error.CommentError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Comment;

/** Adds a comment to a work item and records {@code COMMENTED} (with the first 100 characters) in the same transaction. Comments are not edited or deleted yet. */
@Service
public class AddCommentUseCase {

	static final int MAX_COMMENT_LENGTH = 10_000;

	private static final int AUDIT_PREVIEW_LENGTH = 100;

	private final CommentRepository comments;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	public AddCommentUseCase(CommentRepository comments, AuditRepository audit, TransactionRunner transactions) {
		this.comments = comments;
		this.audit = audit;
		this.transactions = transactions;
	}

	public Result<CommentResponse, CommentError> execute(AddComment command) {
		String content = command.content() == null ? "" : command.content().trim();
		if (content.isEmpty() || content.length() > MAX_COMMENT_LENGTH) {
			return Result.failure(new InvalidComment("Write a comment of up to " + MAX_COMMENT_LENGTH + " characters."));
		}
		return transactions.inTransaction(() -> {
			Comment comment = comments.add(command.workItemCode(), command.actor().userCode(), content).orElse(null);
			if (comment == null) {
				return Result.<CommentResponse, CommentError>failure(new WorkItemNotFound());
			}
			String preview = content.length() <= AUDIT_PREVIEW_LENGTH ? content : content.substring(0, AUDIT_PREVIEW_LENGTH);
			audit.append(new AuditEntry(command.workItemCode(), command.actor().userCode(), ChangeType.COMMENTED, "Comment", null, preview,
					Map.of("commentCode", comment.code().toString())));
			return Result.<CommentResponse, CommentError>success(CommentResponse.from(comment));
		});
	}

}
