package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code WorkItemAssignment} (native queries only). Unassigning deactivates the row. */
interface AssignmentQueries extends Repository<AssignmentEntity, Long> {

	String SELECT_ASSIGNMENT = """
			SELECT a.AssignmentId, w.WorkItemCode, a.AssignmentCode, u.UserCode, u.FullName, a.Role
			FROM WorkItemAssignment a
			JOIN WorkItem w ON w.WorkItemId = a.WorkItemId
			JOIN `User` u ON u.UserId = a.UserId
			""";

	@Query(nativeQuery = true, value = SELECT_ASSIGNMENT
			+ "WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND w.IsActive = TRUE AND a.IsActive = TRUE ORDER BY a.AssignmentId")
	List<AssignmentEntity> findByWorkItem(@Param("workItemCode") String workItemCode);

	/** The assignees of many items in one round trip; {@code codes} is a JSON array of work item UUIDs. */
	@Query(nativeQuery = true, value = SELECT_ASSIGNMENT
			+ "JOIN JSON_TABLE(:codes, '$[*]' COLUMNS (code CHAR(36) PATH '$')) j ON w.WorkItemCode = UUID_TO_BIN(j.code) "
			+ "WHERE w.IsActive = TRUE AND a.IsActive = TRUE ORDER BY a.AssignmentId")
	List<AssignmentEntity> findByWorkItems(@Param("codes") String codes);

	@Query(nativeQuery = true, value = SELECT_ASSIGNMENT
			+ "WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND a.AssignmentCode = UUID_TO_BIN(:assignmentCode) AND w.IsActive = TRUE AND a.IsActive = TRUE")
	Optional<AssignmentEntity> find(@Param("workItemCode") String workItemCode, @Param("assignmentCode") String assignmentCode);

	@Query(nativeQuery = true, value = SELECT_ASSIGNMENT
			+ "WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND u.UserCode = UUID_TO_BIN(:userCode) AND a.Role = :role AND w.IsActive = TRUE AND a.IsActive = TRUE")
	Optional<AssignmentEntity> findActive(@Param("workItemCode") String workItemCode, @Param("userCode") String userCode,
			@Param("role") String role);

	/**
	 * Assigns an active user to an active item. If the same user and role were assigned before and removed, that row
	 * comes back (the unique key covers inactive rows). Inserts nothing (0 rows) if the item or the user does not exist.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemAssignment (AssignmentCode, WorkItemId, UserId, Role)
			SELECT UUID_TO_BIN(:code), w.WorkItemId, u.UserId, :role
			FROM WorkItem w, `User` u
			WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND w.IsActive = TRUE
			  AND u.UserCode = UUID_TO_BIN(:userCode) AND u.IsActive = TRUE AND u.DeletedAt IS NULL
			ON DUPLICATE KEY UPDATE IsActive = TRUE
			""")
	int assign(@Param("code") String code, @Param("workItemCode") String workItemCode, @Param("userCode") String userCode,
			@Param("role") String role);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItemAssignment a JOIN WorkItem w ON w.WorkItemId = a.WorkItemId
			SET a.IsActive = FALSE
			WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND a.AssignmentCode = UUID_TO_BIN(:assignmentCode) AND a.IsActive = TRUE
			""")
	int unassign(@Param("workItemCode") String workItemCode, @Param("assignmentCode") String assignmentCode);

}
