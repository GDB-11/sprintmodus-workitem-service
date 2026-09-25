package com.sprintmodus.workitem_service.adapter.websocket;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.common_lib.security.JwtTokenVerifier;
import com.sprintmodus.common_lib.security.TenantMembershipVerifier;
import com.sprintmodus.common_lib.security.TokenProblem;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway.Failure;

/**
 * Decides who may open a board: {@code /ws/tenant/{tenantId}/project/{projectId}/board?token=JWT}. A browser cannot set
 * headers on a WebSocket, so the token travels in the query string (never logged here). The connection is refused unless
 * the token is valid, the tenant in the URL is the one in the token, the user is still an active member of that tenant and
 * the project exists there. A refused handshake never becomes a WebSocket, so nothing after this needs to trust the URL.
 */
@Component
class BoardHandshakeInterceptor implements HandshakeInterceptor {

	static final Pattern PATH = Pattern.compile("^/ws/tenant/([^/]+)/project/([^/]+)/board/?$");

	private static final Logger log = LoggerFactory.getLogger(BoardHandshakeInterceptor.class);

	private final JwtTokenVerifier tokens;

	private final TenantMembershipVerifier membership;

	private final ProjectServiceGateway projects;

	BoardHandshakeInterceptor(JwtTokenVerifier tokens, TenantMembershipVerifier membership, ProjectServiceGateway projects) {
		this.tokens = tokens;
		this.membership = membership;
		this.projects = projects;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
			Map<String, Object> attributes) {
		Matcher path = PATH.matcher(request.getURI().getPath());
		UUID tenantId = path.matches() ? uuid(path.group(1)) : null;
		UUID projectCode = path.matches() ? uuid(path.group(2)) : null;
		if (tenantId == null || projectCode == null) {
			return refuse(response, HttpStatus.BAD_REQUEST);
		}

		String token = token(request.getURI());
		Result<AuthenticatedUser, TokenProblem> verified = tokens.verify(token);
		if (verified.isFailure()) {
			return refuse(response, HttpStatus.UNAUTHORIZED);
		}
		AuthenticatedUser user = verified.getValue();
		if (!user.tenantId().equals(tenantId)) {
			log.warn("Refused a board connection: user {} of tenant {} asked for tenant {}", user.userCode(), user.tenantId(), tenantId);
			return refuse(response, HttpStatus.FORBIDDEN);
		}

		// Both checks below read the caller's tenant (its database, and project-service on their token)
		TenantScope.enter(user, token);
		try {
			if (!membership.isActiveMember(user.tenantId(), user.userCode())) {
				log.warn("Refused a board connection: user {} is not an active member of tenant {}", user.userCode(), user.tenantId());
				return refuse(response, HttpStatus.UNAUTHORIZED);
			}
			Result<?, Failure> project = projects.getProject(projectCode);
			if (project.isFailure()) {
				return refuse(response, project.getError() == Failure.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.SERVICE_UNAVAILABLE);
			}
		}
		catch (DataAccessException e) {
			log.error("Could not check membership in the database of tenant {}", user.tenantId(), e);
			return refuse(response, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			TenantScope.exit();
		}

		attributes.put(BoardSession.ATTRIBUTE, new BoardSession(user, token, projectCode));
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
	}

	private static boolean refuse(ServerHttpResponse response, HttpStatus status) {
		response.setStatusCode(status);
		return false;
	}

	private static String token(URI uri) {
		return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
	}

	private static UUID uuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
