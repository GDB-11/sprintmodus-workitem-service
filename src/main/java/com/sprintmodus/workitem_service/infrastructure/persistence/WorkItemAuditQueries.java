package com.sprintmodus.workitem_service.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code WorkItemAudit} (native queries only). */
interface WorkItemAuditQueries extends Repository<WorkItemAuditEntity, Long> {

	/**
	 * Blank old/new values and blank additional data are stored as NULL. Inserts nothing (0 rows) if the work item or the
	 * acting user does not exist, which the caller treats as a failure. The item may already be soft-deleted: a deletion is
	 * itself audited.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemAudit (WorkItemId, ChangedBy, ChangeType, FieldChanged, OldValue, NewValue, AdditionalData)
			SELECT w.WorkItemId, u.UserId, :changeType, NULLIF(:field, ''), NULLIF(:oldValue, ''), NULLIF(:newValue, ''),
			       CAST(NULLIF(:additionalData, '') AS JSON)
			FROM WorkItem w, `User` u
			WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND u.UserCode = UUID_TO_BIN(:changedBy)
			""")
	int insert(@Param("workItemCode") String workItemCode, @Param("changedBy") String changedBy,
			@Param("changeType") String changeType, @Param("field") String field, @Param("oldValue") String oldValue,
			@Param("newValue") String newValue, @Param("additionalData") String additionalData);

}
