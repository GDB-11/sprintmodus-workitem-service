package com.sprintmodus.workitem_service.application.service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sprintmodus.workitem_service.application.port.persistence.BurndownRepository;

/**
 * Records the burndown of a sprint whenever something that changes its remaining hours has changed: hours edited, an item
 * finished or reopened, moved into or out of the sprint, deleted, or given a new parent (which decides whether it counts
 * itself or through its children). The daily job the plan first described cannot exist here: this service has no list of
 * tenants (they live in the master database, which it never reads), so instead every change writes today's snapshot, and a
 * day nobody touched is filled in from the day before when the chart is read.
 * <p>
 * It runs <em>after</em> the change has committed and never fails it: a missed snapshot only leaves today's point stale until
 * the next change to the sprint.
 */
@Component
public class BurndownRecorder {

	private static final Logger log = LoggerFactory.getLogger(BurndownRecorder.class);

	private final BurndownRepository burndown;

	public BurndownRecorder(BurndownRepository burndown) {
		this.burndown = burndown;
	}

	/** Records each sprint once (ignoring {@code null}s: an item in the backlog is in no burndown). */
	public void record(UUID... sprintCodes) {
		Set<UUID> done = new HashSet<>();
		for (UUID sprintCode : sprintCodes) {
			if (sprintCode == null || !done.add(sprintCode)) {
				continue;
			}
			try {
				burndown.snapshot(sprintCode);
			}
			catch (RuntimeException e) {
				log.warn("Could not record the burndown of sprint {}", sprintCode, e);
			}
		}
	}

}
