package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.Map;
import java.util.Optional;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;

import java.util.UUID;

/**
 * What the handshake established about a WebSocket connection, kept in the session attributes: the verified user, the
 * token they connected with (verified again on every message, and relayed to project-service) and the one board they may
 * use. Nothing a client sends later can change it.
 */
record BoardSession(AuthenticatedUser user, String token, UUID projectCode) {

	static final String ATTRIBUTE = "sprintmodus.boardSession";

	static Optional<BoardSession> of(Map<String, Object> sessionAttributes) {
		return sessionAttributes != null && sessionAttributes.get(ATTRIBUTE) instanceof BoardSession session ? Optional.of(session)
				: Optional.empty();
	}

	BoardKey board() {
		return new BoardKey(user.tenantId(), projectCode);
	}

	Actor actor() {
		return new Actor(user.userCode(), OrganizationRole.parse(user.role()));
	}

	OnlineUser onlineUser() {
		return new OnlineUser(user.userCode(), user.email());
	}

}
