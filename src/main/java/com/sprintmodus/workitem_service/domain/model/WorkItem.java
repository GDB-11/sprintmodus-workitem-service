package com.sprintmodus.workitem_service.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A work item. Identified everywhere by its {@code code}; {@code number} is a per-project sequence (starting at 1000) for
 * display and search only. The internal database id never leaves the persistence layer.
 *
 * @param projectKey key of the project, for the display key {@code WAR-1000}
 * @param sprintCode the sprint it is planned in, {@code null} for the backlog
 * @param parentCode its parent item, {@code null} for none
 * @param updatedBy who last changed it, {@code null} if nobody has
 * @param boardRank its manual place among the items of its project, type, status and priority (1 = first), {@code null} if
 *        nobody ever ranked it: such items come after the ranked ones of their group, in number order
 * @param childCount how many active children it has
 */
public record WorkItem(UUID code, long number, UUID projectCode, String projectKey, ItemType type, String title,
		String description, String acceptanceCriteria, Priority priority, StatusRef status, UUID sprintCode, UUID parentCode,
		int effortPoints, BigDecimal estimatedHours, BigDecimal remainingHours, UserRef createdBy, UserRef updatedBy,
		Instant createdAt, Instant updatedAt, Integer boardRank, int childCount) {

	/** An item as it was before ranks and child counts existed: never ranked, no children. */
	public WorkItem(UUID code, long number, UUID projectCode, String projectKey, ItemType type, String title, String description,
			String acceptanceCriteria, Priority priority, StatusRef status, UUID sprintCode, UUID parentCode, int effortPoints,
			BigDecimal estimatedHours, BigDecimal remainingHours, UserRef createdBy, UserRef updatedBy, Instant createdAt,
			Instant updatedAt) {
		this(code, number, projectCode, projectKey, type, title, description, acceptanceCriteria, priority, status, sprintCode,
				parentCode, effortPoints, estimatedHours, remainingHours, createdBy, updatedBy, createdAt, updatedAt, null, 0);
	}

	/** {@code WAR-1000}. */
	public String displayKey() {
		return projectKey + "-" + number;
	}

}
