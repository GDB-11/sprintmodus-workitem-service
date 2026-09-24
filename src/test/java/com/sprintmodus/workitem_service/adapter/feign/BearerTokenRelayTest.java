package com.sprintmodus.workitem_service.adapter.feign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sprintmodus.common_lib.security.AuthenticatedUser;

import feign.RequestTemplate;

class BearerTokenRelayTest {

	private final BearerTokenRelay relay = new BearerTokenRelay();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private static AuthenticatedUser user() {
		return new AuthenticatedUser(UUID.randomUUID(), "a@b.io", UUID.randomUUID(), "acme", "OWNER", "FREE", 1, 5, 100);
	}

	private RequestTemplate apply() {
		RequestTemplate template = new RequestTemplate();
		relay.apply(template);
		return template;
	}

	@Test
	void forwardsTheTokenOfTheAuthenticatedCaller() {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user(), "the.jwt.token",
				List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

		assertThat(apply().headers().get("Authorization")).containsExactly("Bearer the.jwt.token");
	}

	@Test
	void addsNothingWithoutAnAuthenticatedCaller() {
		assertThat(apply().headers()).doesNotContainKey("Authorization");
	}

	@Test
	void addsNothingForAnAnonymousCaller() {
		SecurityContextHolder.getContext().setAuthentication(
				new AnonymousAuthenticationToken("key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

		assertThat(apply().headers()).doesNotContainKey("Authorization");
	}

	@Test
	void addsNothingWhenTheCredentialsAreNotAToken() {
		for (Object credentials : new Object[] { null, "", "  " }) {
			SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user(), credentials, List.of()));

			assertThat(apply().headers()).as(String.valueOf(credentials)).doesNotContainKey("Authorization");
		}
	}

	@Test
	void addsNothingWhenThePrincipalIsNotOurAuthenticatedUser() {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("someone", "a.token", List.of()));

		assertThat(apply().headers()).doesNotContainKey("Authorization");
	}

}
