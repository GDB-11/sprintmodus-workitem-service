package com.sprintmodus.workitem_service.application.event;

import java.time.Instant;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.event.DomainEvent;

/**
 * What is actually published: a domain event, the tenant it happened in and when it was published (right after the commit).
 * Use cases know neither tenants nor time, so the publisher adds both; a listener (and later a separate notification service)
 * must never have to guess whose event it is.
 */
public record TenantEvent(UUID tenantId, Instant occurredAt, DomainEvent event) {
}
