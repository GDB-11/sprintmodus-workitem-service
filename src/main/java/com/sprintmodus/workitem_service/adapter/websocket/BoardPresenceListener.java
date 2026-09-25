package com.sprintmodus.workitem_service.adapter.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * A connection that ends without saying goodbye (a closed tab, a lost network) still leaves the board: Spring reports
 * every ended session, and {@link WebSocketSessionManager#unregisterSession} makes a second notice for one that already
 * said goodbye a no-op.
 */
@Component
class BoardPresenceListener {

	private final WebSocketSessionManager sessions;

	private final BoardBroadcaster broadcaster;

	BoardPresenceListener(WebSocketSessionManager sessions, BoardBroadcaster broadcaster) {
		this.sessions = sessions;
		this.broadcaster = broadcaster;
	}

	@EventListener
	void onDisconnect(SessionDisconnectEvent event) {
		sessions.unregisterSession(event.getSessionId())
				.ifPresent(departure -> broadcaster.userDisconnected(departure.board(), departure.user(), departure.usersOnline()));
	}

}
