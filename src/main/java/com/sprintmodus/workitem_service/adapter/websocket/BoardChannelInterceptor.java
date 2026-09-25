package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import com.sprintmodus.common_lib.security.JwtTokenVerifier;

/**
 * The gate every STOMP frame passes through, on the inbound channel:
 * <ul>
 * <li>{@code SUBSCRIBE}: only the connection's own board topic and its private error queue. This is what keeps a tenant
 * from ever receiving another tenant's events, even if a client asks for their topic by name.</li>
 * <li>{@code SEND}: only under the connection's own board, and only while its token is still valid (a socket can outlive a
 * token; the client reconnects with a fresh one).</li>
 * </ul>
 * Handlers run on other threads, so {@link #beforeHandle} points that thread at the caller's tenant and
 * {@link #afterMessageHandled} clears it.
 */
@Component
class BoardChannelInterceptor implements ExecutorChannelInterceptor {

	static final String ERROR_QUEUE = "/user/queue/errors";

	private static final Logger log = LoggerFactory.getLogger(BoardChannelInterceptor.class);

	private final JwtTokenVerifier tokens;

	BoardChannelInterceptor(JwtTokenVerifier tokens) {
		this.tokens = tokens;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		StompCommand command = accessor.getCommand();
		if (command == null || command == StompCommand.DISCONNECT) {
			return message;
		}
		BoardSession session = BoardSession.of(accessor.getSessionAttributes()).orElse(null);
		if (session == null) {
			throw new MessageDeliveryException(message, "Not connected to a board.");
		}
		String destination = accessor.getDestination();

		if (command == StompCommand.SUBSCRIBE) {
			if (!session.board().topic().equals(destination) && !ERROR_QUEUE.equals(destination)) {
				log.warn("Refused a subscription by user {} of tenant {} outside their board", session.user().userCode(), session.user().tenantId());
				throw new MessageDeliveryException(message, "You can only subscribe to your own board.");
			}
		}
		else if (command == StompCommand.SEND) {
			if (destination == null || !destination.startsWith(session.board().applicationPrefix())) {
				log.warn("Refused a message by user {} of tenant {} outside their board", session.user().userCode(), session.user().tenantId());
				throw new MessageDeliveryException(message, "You can only send to your own board.");
			}
			if (tokens.verify(session.token()).isFailure()) {
				throw new MessageDeliveryException(message, "The token has expired. Reconnect to continue.");
			}
		}
		return message;
	}

	@Override
	public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
		Optional<BoardSession> session = BoardSession.of(StompHeaderAccessor.wrap(message).getSessionAttributes());
		session.ifPresent(s -> TenantScope.enter(s.user(), s.token()));
		return message;
	}

	@Override
	public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
		TenantScope.exit();
	}

}
