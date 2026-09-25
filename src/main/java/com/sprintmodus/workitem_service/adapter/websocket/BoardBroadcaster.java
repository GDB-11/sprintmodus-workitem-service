package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.sprintmodus.common_lib.result.ApplicationError;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.BoardMessage;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.CommentAddedData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.ErrorData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.ItemMovedData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.ItemsReorderedData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.PresenceData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.StatusChangedData;
import com.sprintmodus.workitem_service.adapter.websocket.BoardMessages.UserTypingData;
import com.sprintmodus.workitem_service.application.dto.Responses.CommentResponse;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.service.GetWorkItemUseCase;

/**
 * Publishes to a board's topic. Used by the WebSocket handlers and by the REST controllers alike, so a status change or a
 * comment reaches every open board however it was made. The change is already committed when this runs: a broadcast that
 * fails is logged and never turns a successful change into an error for the caller (clients that miss an event catch up
 * on their next refresh).
 */
@Component
public class BoardBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(BoardBroadcaster.class);

	private final SimpMessagingTemplate messaging;

	private final GetWorkItemUseCase getWorkItem;

	BoardBroadcaster(SimpMessagingTemplate messaging, GetWorkItemUseCase getWorkItem) {
		this.messaging = messaging;
		this.getWorkItem = getWorkItem;
	}

	public void statusChanged(UUID tenantId, WorkItemResponse item) {
		publish(new BoardKey(tenantId, item.projectCode()), BoardMessage.ITEM_STATUS_CHANGED, new StatusChangedData(item.code(),
				item.displayKey(), Responses.Status.from(item.status()), statuses(item), Responses.User.from(item.updatedBy()), item.updatedAt()));
	}

	void itemMoved(UUID tenantId, WorkItemResponse item, String fromStatus, Integer position) {
		publish(new BoardKey(tenantId, item.projectCode()), BoardMessage.ITEM_MOVED, new ItemMovedData(item.code(), item.displayKey(),
				fromStatus, Responses.Status.from(item.status()), statuses(item), position, Responses.User.from(item.updatedBy()),
				item.updatedAt()));
	}

	/** A card was put in another place of its column: other boards reload it (the order is not part of the event). */
	public void itemsReordered(UUID tenantId, WorkItemResponse item) {
		publish(new BoardKey(tenantId, item.projectCode()), BoardMessage.ITEMS_REORDERED,
				new ItemsReorderedData(item.code(), item.displayKey(), Responses.Status.from(item.status())));
	}

	/** For callers that only know the work item (the REST endpoint): the board is found from it. */
	public void commentAdded(UUID tenantId, UUID workItemCode, CommentResponse comment) {
		var item = getWorkItem.execute(workItemCode);
		if (item.isSuccess()) {
			commentAdded(new BoardKey(tenantId, item.getValue().projectCode()), comment);
		}
	}

	void commentAdded(BoardKey board, CommentResponse comment) {
		publish(board, BoardMessage.COMMENT_ADDED, new CommentAddedData(comment.workItemCode(), Responses.Comment.from(comment)));
	}

	void userTyping(BoardKey board, UUID workItemCode, OnlineUser user) {
		publish(board, BoardMessage.USER_TYPING, new UserTypingData(workItemCode, user));
	}

	void userConnected(BoardKey board, OnlineUser user, List<OnlineUser> usersOnline) {
		publish(board, BoardMessage.USER_CONNECTED, new PresenceData(user, usersOnline));
	}

	void userDisconnected(BoardKey board, OnlineUser user, List<OnlineUser> usersOnline) {
		publish(board, BoardMessage.USER_DISCONNECTED, new PresenceData(user, usersOnline));
	}

	/** Tells only the user whose message failed. */
	void error(UUID userCode, String code, String message, UUID workItemCode) {
		try {
			messaging.convertAndSendToUser(userCode.toString(), "/queue/errors", new BoardMessage(BoardMessage.ERROR,
					new ErrorData(code, message, workItemCode)));
		}
		catch (RuntimeException e) {
			log.warn("Could not tell user {} about a failed board message", userCode, e);
		}
	}

	void error(UUID userCode, ApplicationError error, UUID workItemCode) {
		error(userCode, error.code(), error.message(), workItemCode);
	}

	private static List<Responses.Status> statuses(WorkItemResponse item) {
		return item.allowedStatuses().stream().map(Responses.Status::from).toList();
	}

	private void publish(BoardKey board, String type, Object data) {
		try {
			messaging.convertAndSend(board.topic(), new BoardMessage(type, data));
		}
		catch (RuntimeException e) {
			log.warn("Could not broadcast {} to board {}", type, board, e);
		}
	}

}
