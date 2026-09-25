package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.common_lib.tenant.TenantContext;

/**
 * What {@code TenantSecurityFilter} does for an HTTP request, for a thread handling a WebSocket message: points the thread
 * at the caller's tenant database and lets outgoing calls relay their token. Always {@link #exit()} in a {@code finally}
 * (or the interceptor's after-handle hook): pooled threads must not keep a tenant.
 */
final class TenantScope {

	private TenantScope() {
	}

	static void enter(AuthenticatedUser user, String token) {
		TenantContext.set(user.tenantId(), user.userCode(), user.maxProjects(), user.maxUsers(), user.maxStorageMB());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, token,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
		SecurityContextHolder.setContext(context);
	}

	static void exit() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
	}

}
