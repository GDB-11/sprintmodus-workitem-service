package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.Requests;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.adapter.websocket.BoardBroadcaster;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.service.AddCommentUseCase;
import com.sprintmodus.workitem_service.application.service.ListCommentsUseCase;

/** {@code GET} and {@code POST} only: comments are not edited or deleted yet. A new comment is also broadcast to the project's open boards. */
@RestController
@RequestMapping("/api/work-items/{workItemCode}/comments")
class CommentController {

	private final ListCommentsUseCase listComments;

	private final AddCommentUseCase addComment;

	private final BoardBroadcaster board;

	CommentController(ListCommentsUseCase listComments, AddCommentUseCase addComment, BoardBroadcaster board) {
		this.listComments = listComments;
		this.addComment = addComment;
		this.board = board;
	}

	@GetMapping
	ResponseEntity<?> list(@PathVariable UUID workItemCode) {
		return listComments.execute(workItemCode).fold(comments -> ResponseEntity.ok(comments.stream().map(Responses.Comment::from).toList()),
				ErrorMapper::toResponse);
	}

	@PostMapping
	ResponseEntity<?> add(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode, @RequestBody Requests.Comment request) {
		return addComment.execute(new AddComment(Actors.from(user), workItemCode, request.content()))
				.tap(comment -> board.commentAdded(user.tenantId(), workItemCode, comment))
				.fold(comment -> ResponseEntity.status(HttpStatus.CREATED).body(Responses.Comment.from(comment)), ErrorMapper::toResponse);
	}

}
