package com.sprintmodus.workitem_service.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.Warning;

/**
 * Keeps a sprint's recorded velocity equal to the effort completed in it. This service owns the items, so it computes the
 * number; project-service owns the sprint and stores it.
 * <p>
 * It runs <em>after</em> the change has committed, and never inside a transaction: a slow or unavailable project-service must
 * not undo or block the change. A failure only leaves the velocity stale until the next change to the sprint, and is
 * reported as a warning.
 */
@Component
public class SprintVelocitySync {

	private static final Logger log = LoggerFactory.getLogger(SprintVelocitySync.class);

	private final WorkItemRepository items;

	private final ProjectServiceGateway projects;

	public SprintVelocitySync(WorkItemRepository items, ProjectServiceGateway projects) {
		this.items = items;
		this.projects = projects;
	}

	/** Refreshes each sprint (ignoring {@code null}s and repeats); returns a warning for every one that could not be. */
	public List<Warning> refresh(UUID... sprintCodes) {
		List<Warning> warnings = new ArrayList<>();
		java.util.Set<UUID> done = new java.util.HashSet<>();
		for (UUID sprintCode : sprintCodes) {
			if (sprintCode == null || !done.add(sprintCode)) {
				continue;
			}
			int completed = items.completedEffortOfSprint(sprintCode);
			if (projects.updateSprintVelocity(sprintCode, completed).isFailure()) {
				log.warn("Could not record the velocity of sprint {}", sprintCode);
				warnings.add(new Warning(Warning.VELOCITY_NOT_UPDATED,
						"The sprint's velocity could not be updated right now. It will be corrected on the next change."));
			}
		}
		return warnings;
	}

}
