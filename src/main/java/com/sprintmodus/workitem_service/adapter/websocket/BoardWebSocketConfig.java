package com.sprintmodus.workitem_service.adapter.websocket;

import java.security.Principal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * STOMP over WebSocket for the real-time board. Clients connect to
 * {@code /ws/tenant/{tenantId}/project/{projectId}/board}, publish under {@code /app/...} and listen on
 * {@code /topic/tenant/{tenantId}/project/{projectId}}. The broker is the in-memory simple broker: no message broker exists
 * for now, so events reach the clients of this instance only.
 * <p>
 * Who may connect is decided by {@link BoardHandshakeInterceptor}, and what a connection may subscribe to or send by
 * {@link BoardChannelInterceptor}; see {@link WebSocketSecurityConfig} for why the endpoint sits outside the HTTP token
 * filter.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class BoardWebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final BoardHandshakeInterceptor handshake;

	private final BoardChannelInterceptor channelInterceptor;

	private final TaskScheduler heartbeatScheduler;

	private final String[] allowedOrigins;

	/** Spring's own broker scheduler, injected lazily: it does not exist yet while the broker is being configured. */
	BoardWebSocketConfig(BoardHandshakeInterceptor handshake, BoardChannelInterceptor channelInterceptor,
			@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler,
			@Value("${sprintmodus.websocket.allowed-origins:http://localhost:4200}") String[] allowedOrigins) {
		this.handshake = handshake;
		this.channelInterceptor = channelInterceptor;
		this.heartbeatScheduler = heartbeatScheduler;
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// Browsers send an Origin header, and the gateway forwards it: only the configured front ends may connect
		registry.addEndpoint("/ws/tenant/*/project/*/board").addInterceptors(handshake)
				.setHandshakeHandler(new UserNamedByCode()).setAllowedOriginPatterns(allowedOrigins);
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.setApplicationDestinationPrefixes("/app");
		registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[] { 10_000, 10_000 })
				.setTaskScheduler(heartbeatScheduler);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(channelInterceptor);
	}

	/** The STOMP user is the user's code, so a message to one user reaches all their tabs and no one else. */
	private static final class UserNamedByCode extends DefaultHandshakeHandler {

		@Override
		protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, Map<String, Object> attributes) {
			return BoardSession.of(attributes).<Principal>map(session -> session.user().userCode()::toString).orElse(null);
		}

	}

}
