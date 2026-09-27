package com.sprintmodus.workitem_service.infrastructure.event;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.sprintmodus.common_lib.tenant.TenantContext;
import com.sprintmodus.workitem_service.application.event.TenantEvent;
import com.sprintmodus.workitem_service.application.port.external.DomainEventPublisher;
import com.sprintmodus.workitem_service.domain.event.DomainEvent;

/**
 * Publishes {@link TenantEvent}s through Spring's {@link ApplicationEventPublisher}; listen with {@code @EventListener}.
 * Listeners run synchronously on the caller's thread, inside its tenant context, so a listener that does slow or remote work
 * must hand it off ({@code @Async}) rather than hold up the request. A failing listener is logged, never propagated.
 */
@Component
class SpringDomainEventPublisher implements DomainEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(SpringDomainEventPublisher.class);

	private final ApplicationEventPublisher publisher;

	private final Clock clock;

	@Autowired
	SpringDomainEventPublisher(ApplicationEventPublisher publisher, ObjectProvider<Clock> clock) {
		this(publisher, clock.getIfAvailable(Clock::systemUTC));
	}

	SpringDomainEventPublisher(ApplicationEventPublisher publisher, Clock clock) {
		this.publisher = publisher;
		this.clock = clock;
	}

	@Override
	public void publish(DomainEvent event) {
		try {
			publisher.publishEvent(new TenantEvent(TenantContext.requireCurrentTenant(), clock.instant(), event));
		}
		catch (RuntimeException e) {
			log.warn("Could not publish {} for work item {}", event.getClass().getSimpleName(), event.workItemCode(), e);
		}
	}

}
