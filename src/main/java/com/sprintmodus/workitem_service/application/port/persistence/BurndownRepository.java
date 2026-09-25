package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.UUID;

/**
 * The burndown snapshots of the current tenant's sprints. A burndown cannot be rebuilt afterwards, so it is recorded as
 * the work happens.
 */
public interface BurndownRepository {

	/**
	 * Stores today's snapshot of a sprint: the hours still to do and the ideal line's value for today. The last snapshot of
	 * a day is that day's end-of-day value, so calling this after every change is right. Does nothing unless the sprint is
	 * ACTIVE: a planned sprint has no burndown yet and a closed one's data is locked. It is one statement in a transaction of
	 * its own, so the caller's change is already committed when it runs.
	 */
	void snapshot(UUID sprintCode);

}
