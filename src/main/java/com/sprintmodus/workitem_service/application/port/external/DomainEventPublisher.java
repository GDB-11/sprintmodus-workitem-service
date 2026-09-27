package com.sprintmodus.workitem_service.application.port.external;

import com.sprintmodus.workitem_service.domain.event.DomainEvent;

/**
 * The seam for automation (notifications and the like) that does not exist yet. Use cases call it after their transaction has
 * committed. Publishing is best effort and never throws: an event nobody could take must not turn a change that was saved into
 * an error for the caller. Today the only implementation is in-process; there is deliberately no broker behind it.
 */
public interface DomainEventPublisher {

	void publish(DomainEvent event);

}
