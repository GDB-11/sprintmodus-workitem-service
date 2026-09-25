package com.sprintmodus.workitem_service.adapter.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The shared security chain (common-lib) accepts a bearer header under {@code /api/**} and denies everything else. A
 * browser cannot send a header when opening a WebSocket, so {@code /ws/**} has its own chain that lets the request through
 * to {@link BoardHandshakeInterceptor}, which verifies the token from the query string and refuses the handshake itself.
 * Nothing is served under {@code /ws/**} except that handshake: a request that does not pass it never becomes a WebSocket.
 */
@Configuration(proxyBeanMethods = false)
class WebSocketSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain webSocketSecurityFilterChain(HttpSecurity http) {
		return http.securityMatcher("/ws/**")
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.build();
	}

}
