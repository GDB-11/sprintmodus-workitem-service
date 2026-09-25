package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.CommentAddedMessage;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.ItemMovedMessage;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.StatusChangeMessage;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.UserTypingMessage;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.dto.Commands.ChangeStatus;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.service.AddCommentUseCase;
import com.sprintmodus.workitem_service.application.service.ChangeWorkItemStatusUseCase;
import com.sprintmodus.workitem_service.application.service.GetWorkItemUseCase;

/**
 * The board's STOMP handlers: {@code /app/tenant/{t}/project/{p}/{status-changed|item-moved|comment-added|connect|
 * disconnect|user-typing}}. They only translate between messages and use cases (the same ones the REST endpoints use) and
 * broadcast the outcome. The board, the tenant and the user come from the connection's {@link BoardSession}, which the
 * channel interceptor has already checked against the destination; the URL variables are never trusted. A failure is
 * reported to the sender alone.
 */
@Controller
class BoardController {

	private static final Logger log = LoggerFactory.getLogger(BoardController.class);

	private final GetWorkItemUseCase getWorkItem;

	private final ChangeWorkItemStatusUseCase changeStatus;

	private final AddCommentUseCase addComment;

	private final BoardBroadcaster broadcaster;

	private final WebSocketSessionManager sessions;

	private final TypingThrottle typing;

	BoardController(GetWorkItemUseCase getWorkItem, ChangeWorkItemStatusUseCase changeStatus, AddCommentUseCase addComment,
			BoardBroadcaster broadcaster, WebSocketSessionManager sessions, TypingThrottle typing) {
		this.getWorkItem = getWorkItem;
		this.changeStatus = changeStatus;
		this.addComment = addComment;
		this.broadcaster = broadcaster;
		this.sessions = sessions;
		this.typing = typing;
	}

	@MessageMapping("/tenant/{tenantId}/project/{projectId}/status-changed")
	void statusChanged(@Payload StatusChangeMessage message, SimpMessageHeaderAccessor headers) {
		BoardSession session = session(headers);
		WorkItemResponse item = itemOnBoard(session, message.workItemCode());
		if (item == null) {
			return;
		}
		changeStatus.execute(new ChangeStatus(session.actor(), message.workItemCode(), message.status()))
				.tap(changed -> broadcaster.statusChanged(session.user().tenantId(), changed))
				.tapError(error -> broadcaster.error(session.user().userCode(), error, message.workItemCode()));
	}

	/**
	 * A card dropped in a column. Moving to another column is a status change, validated against the workflow like any
	 * other. The order of cards within a column is not stored anywhere (work items have no rank), so a drop into the column
	 * the card is already in changes nothing and is not broadcast; {@code position} is only passed on with real moves.
	 */
	@MessageMapping("/tenant/{tenantId}/project/{projectId}/item-moved")
	void itemMoved(@Payload ItemMovedMessage message, SimpMessageHeaderAccessor headers) {
		BoardSession session = session(headers);
		WorkItemResponse item = itemOnBoard(session, message.workItemCode());
		if (item == null || item.status().code().equalsIgnoreCase(message.status() == null ? "" : message.status().trim())) {
			return;
		}
		String from = item.status().code();
		changeStatus.execute(new ChangeStatus(session.actor(), message.workItemCode(), message.status()))
				.tap(moved -> broadcaster.itemMoved(session.user().tenantId(), moved, from, message.position()))
				.tapError(error -> broadcaster.error(session.user().userCode(), error, message.workItemCode()));
	}

	@MessageMapping("/tenant/{tenantId}/project/{projectId}/comment-added")
	void commentAdded(@Payload CommentAddedMessage message, SimpMessageHeaderAccessor headers) {
		BoardSession session = session(headers);
		if (itemOnBoard(session, message.workItemCode()) == null) {
			return;
		}
		addComment.execute(new AddComment(session.actor(), message.workItemCode(), message.content()))
				.tap(comment -> broadcaster.commentAdded(session.board(), comment))
				.tapError(error -> broadcaster.error(session.user().userCode(), error, message.workItemCode()));
	}

	@MessageMapping("/tenant/{tenantId}/project/{projectId}/connect")
	void connect(SimpMessageHeaderAccessor headers) {
		BoardSession session = session(headers);
		var online = sessions.registerSession(headers.getSessionId(), session.board(), session.onlineUser());
		broadcaster.userConnected(session.board(), session.onlineUser(), online);
	}

	/** The polite goodbye; a dropped connection is handled by {@link BoardPresenceListener}. */
	@MessageMapping("/tenant/{tenantId}/project/{projectId}/disconnect")
	void disconnect(SimpMessageHeaderAccessor headers) {
		sessions.unregisterSession(headers.getSessionId())
				.ifPresent(departure -> broadcaster.userDisconnected(departure.board(), departure.user(), departure.usersOnline()));
	}

	@MessageMapping("/tenant/{tenantId}/project/{projectId}/user-typing")
	void userTyping(@Payload UserTypingMessage message, SimpMessageHeaderAccessor headers) {
		BoardSession session = session(headers);
		if (message.workItemCode() != null && typing.allow(session.user().userCode(), message.workItemCode())) {
			broadcaster.userTyping(session.board(), message.workItemCode(), session.onlineUser());
		}
	}

	/** Anything unexpected (a payload that is not the expected JSON, a database that is down) is told to the sender only, without detail. */
	@MessageExceptionHandler
	void failed(Exception exception, SimpMessageHeaderAccessor headers) {
		log.warn("A board message failed", exception);
		BoardSession.of(headers.getSessionAttributes()).ifPresent(session -> broadcaster.error(session.user().userCode(), "INTERNAL_ERROR",
				"The message could not be processed.", null));
	}

	private static BoardSession session(SimpMessageHeaderAccessor headers) {
		return BoardSession.of(headers.getSessionAttributes()).orElseThrow(() -> new IllegalStateException("No board session"));
	}

	/** The work item, if it exists and is on this connection's board; otherwise the sender is told and this returns {@code null}. */
	private WorkItemResponse itemOnBoard(BoardSession session, UUID workItemCode) {
		if (workItemCode == null) {
			broadcaster.error(session.user().userCode(), "INVALID_MESSAGE", "Say which work item.", null);
			return null;
		}
		Result<WorkItemResponse, ?> found = getWorkItem.execute(workItemCode);
		if (found.isFailure() || !found.getValue().projectCode().equals(session.projectCode())) {
			// the same answer for "does not exist" and "is in another project": a board reveals nothing about others
			broadcaster.error(session.user().userCode(), "WORK_ITEM_NOT_FOUND", "The work item was not found on this board.", workItemCode);
			return null;
		}
		return found.getValue();
	}

}
