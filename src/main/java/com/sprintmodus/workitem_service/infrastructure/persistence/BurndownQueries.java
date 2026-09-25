package com.sprintmodus.workitem_service.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code BurndownData} this service issues (native queries only). */
interface BurndownQueries extends Repository<BurndownEntity, Long> {

	/**
	 * Stores the snapshot the {@code SprintBurndownToday} view describes (V1.11: remaining hours, the linear ideal, and the
	 * day it belongs to), replacing an earlier one of the same day. The view only has ACTIVE sprints, so a planned or closed
	 * sprint matches nothing.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO BurndownData (SprintId, SnapshotDate, RemainingHours, IdealRemainingHours)
			SELECT b.SprintId, b.SnapshotDate, b.RemainingHours, b.IdealRemainingHours
			FROM SprintBurndownToday b JOIN Sprint s ON s.SprintId = b.SprintId
			WHERE s.SprintCode = UUID_TO_BIN(:sprintCode)
			ON DUPLICATE KEY UPDATE RemainingHours = VALUES(RemainingHours), IdealRemainingHours = VALUES(IdealRemainingHours)
			""")
	int snapshot(@Param("sprintCode") String sprintCode);

}
