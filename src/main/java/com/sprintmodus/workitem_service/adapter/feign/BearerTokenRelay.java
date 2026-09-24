package com.sprintmodus.workitem_service.adapter.feign;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.sprintmodus.common_lib.security.AuthenticatedUser;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Forwards the caller's JWT on calls to other services, so project-service can authenticate the same user and route to
 * the same tenant database. Only the token that {@code TenantSecurityFilter} stored is forwarded (recognized by its
 * {@link AuthenticatedUser} principal, so an anonymous caller's empty credentials are never sent). With no such caller
 * (a background job) nothing is added and the callee rejects the call, which is the safe outcome.
 */
@Component
class BearerTokenRelay implements RequestInterceptor {

	@Override
	public void apply(RequestTemplate template) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser
				&& authentication.getCredentials() instanceof String token && !token.isBlank()) {
			template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
	}

}
