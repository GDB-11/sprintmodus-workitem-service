package com.sprintmodus.workitem_service.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.sprintmodus.common_lib.tenant.TenantContext;
import com.sprintmodus.workitem_service.application.event.TenantEvent;
import com.sprintmodus.workitem_service.domain.event.DomainEvent;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.WorkItemCommentAdded;

/** The in-process seam: events leave with the tenant they happened in, and a failure never reaches the caller. */
class SpringDomainEventPublisherTest {

	private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

	private final List<Object> received = new ArrayList<>();

	private final DomainEvent event = new WorkItemCommentAdded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private SpringDomainEventPublisher publisher(ApplicationEventPublisher spring) {
		return new SpringDomainEventPublisher(spring, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void publishesTheEventWithTheCurrentTenantAndTheTime() {
		UUID tenant = UUID.randomUUID();
		TenantContext.setCurrentTenant(tenant);

		publisher(received::add).publish(event);

		assertThat(received).containsExactly(new TenantEvent(tenant, NOW, event));
	}

	@Test
	void aFailingListenerIsSwallowed() {
		TenantContext.setCurrentTenant(UUID.randomUUID());

		assertThatCode(() -> publisher(_ -> {
			throw new IllegalStateException("listener broke");
		}).publish(event)).doesNotThrowAnyException();
	}

	@Test
	void noTenantMeansNoEventAndNoError() {
		assertThatCode(() -> publisher(received::add).publish(event)).doesNotThrowAnyException();

		assertThat(received).isEmpty();
	}

}
