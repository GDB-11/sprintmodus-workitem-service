package com.sprintmodus.workitem_service.adapter.websocket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;

/**
 * The JSON of the board protocol. Inbound messages carry only what the client decides; who is asking and which board it is
 * always come from the handshake, never from the payload. Outbound data reuses the REST response types for statuses, users
 * and comments, so an event looks exactly like what the same change returns over HTTP.
 */
public final class BoardMessages {

	private BoardMessages() {
	}

	/** Everything broadcast to a board, and what a client is told about its own failures, has this envelope. */
	public record BoardMessage(String type, Object data) {

		public static final String ITEM_STATUS_CHANGED = "ITEM_STATUS_CHANGED";

		public static final String ITEM_MOVED = "ITEM_MOVED";

		public static final String COMMENT_ADDED = "COMMENT_ADDED";

		public static final String ITEMS_REORDERED = "ITEMS_REORDERED";

		public static final String USER_TYPING = "USER_TYPING";

		public static final String USER_CONNECTED = "USER_CONNECTED";

		public static final String USER_DISCONNECTED = "USER_DISCONNECTED";

		/** Only ever sent to the client whose message failed, on {@code /user/queue/errors}. */
		public static final String ERROR = "ERROR";

	}

	// ---------------------------------------------------------------- client -> server

	public record StatusChangeMessage(UUID workItemCode, String status) {
	}

	/** {@code position} is the card's place in the target column; it is passed on to the other clients, not stored. */
	public record ItemMovedMessage(UUID workItemCode, String status, Integer position) {
	}

	public record CommentAddedMessage(UUID workItemCode, String content) {
	}

	public record UserTypingMessage(UUID workItemCode) {
	}

	// ---------------------------------------------------------------- server -> clients

	public record StatusChangedData(UUID workItemCode, String displayKey, Responses.Status status,
			List<Responses.Status> allowedStatuses, Responses.User changedBy, Instant updatedAt) {
	}

	public record ItemMovedData(UUID workItemCode, String displayKey, String fromStatus, Responses.Status status,
			List<Responses.Status> allowedStatuses, Integer position, Responses.User movedBy, Instant updatedAt) {
	}

	/** Some cards of a column were put in another order; clients reload the column (the new order is not in the message). */
	public record ItemsReorderedData(UUID workItemCode, String displayKey, Responses.Status status) {
	}

	public record CommentAddedData(UUID workItemCode, Responses.Comment comment) {
	}

	public record UserTypingData(UUID workItemCode, OnlineUser user) {
	}

	public record PresenceData(OnlineUser user, List<OnlineUser> usersOnline) {
	}

	public record ErrorData(String code, String message, UUID workItemCode) {
	}

}
