package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every SQL statement on {@code WorkItem}, written out. The interface extends the bare {@link Repository} marker on purpose,
 * so no generated query is available: each method is a native query. UUIDs are {@code BINARY(16)}: written with
 * {@code UUID_TO_BIN()}, read into a {@code UUID}. Writes must run in the caller's transaction.
 * <p>
 * Projects and sprints belong to project-service. The joins to {@code Project} and {@code Sprint} here only translate
 * between the UUID codes the API speaks and the internal ids the foreign keys use; the rules about them (a sprint's status,
 * its project) are asked of project-service.
 * <p>
 * Every {@code @Modifying} query clears the persistence context: a native query that returns an entity hands back the
 * instance already loaded in the transaction if there is one, so without it a read after an update would be stale.
 */
interface WorkItemQueries extends Repository<WorkItemEntity, Long> {

	String SELECT_ITEM = """
			SELECT w.WorkItemId, w.WorkItemCode, w.WorkItemNumber, p.ProjectCode, p.`Key` AS ProjectKey, w.Type, w.Title,
			       w.Description, w.AcceptanceCriteria, w.Priority, w.BoardRank,
			       (SELECT COUNT(*) FROM WorkItem c WHERE c.ParentId = w.WorkItemId AND c.IsActive = TRUE) AS ChildCount,
			       st.StatusCode, st.DisplayName AS StatusName,
			       (st.SortOrder = (SELECT MIN(first.SortOrder) FROM WorkItemStatus first
			                       WHERE first.ItemType = st.ItemType AND first.IsActive = TRUE)) AS StatusInitial,
			       st.IsTerminal AS StatusTerminal, s.SprintCode, pa.WorkItemCode AS ParentCode,
			       w.EffortPoints, w.EstimatedHours, w.RemainingHours, cu.UserCode AS CreatedByCode,
			       cu.FullName AS CreatedByName, uu.UserCode AS UpdatedByCode, uu.FullName AS UpdatedByName,
			       w.CreatedAt, w.UpdatedAt
			""";

	String FROM_ITEM = """
			FROM WorkItem w
			JOIN Project p ON p.ProjectId = w.ProjectId
			JOIN WorkItemStatus st ON st.StatusId = w.StatusId
			JOIN `User` cu ON cu.UserId = w.CreatedBy
			LEFT JOIN `User` uu ON uu.UserId = w.UpdatedBy
			LEFT JOIN Sprint s ON s.SprintId = w.SprintId
			LEFT JOIN WorkItem pa ON pa.WorkItemId = w.ParentId
			""";

	/**
	 * The filter of the list. Each optional condition has an on/off flag (0 or 1) next to its value, and the value is always
	 * bound (a placeholder when off): a parameter that is sometimes NULL has no reliable type in a native query.
	 */
	String SEARCH_WHERE = """
			WHERE w.IsActive = TRUE AND p.IsActive = TRUE
			  AND (:hasProject = 0 OR p.ProjectCode = UUID_TO_BIN(:project))
			  AND (:sprintMode = 0 OR (:sprintMode = 1 AND w.SprintId IS NULL) OR (:sprintMode = 2 AND s.SprintCode = UUID_TO_BIN(:sprint)))
			  AND (:hasType = 0 OR w.Type = :type)
			  AND (:hasStatus = 0 OR st.StatusCode = :status)
			  AND (:hasParent = 0 OR pa.WorkItemCode = UUID_TO_BIN(:parent))
			  AND (:hasPriority = 0 OR w.Priority = :priority)
			  AND (:hasAssignee = 0 OR EXISTS (
			        SELECT 1 FROM WorkItemAssignment a JOIN `User` au ON au.UserId = a.UserId
			        WHERE a.WorkItemId = w.WorkItemId AND a.IsActive = TRUE AND au.UserCode = UUID_TO_BIN(:assignee)))
			  AND (:hasText = 0 OR MATCH (w.Title, w.Description) AGAINST (:text IN NATURAL LANGUAGE MODE))
			""";

	/**
	 * The list's order: newest number first, or (when {@code :board} is 1) the order of a board column: by priority, CRITICAL
	 * first, then by manual rank (unranked items last), then by number.
	 */
	String ORDER_BY = """
			ORDER BY CASE WHEN :board = 1 THEN FIELD(w.Priority, 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW') ELSE 0 END,
			         CASE WHEN :board = 1 THEN w.BoardRank IS NULL ELSE 0 END,
			         CASE WHEN :board = 1 THEN w.BoardRank ELSE 0 END,
			         CASE WHEN :board = 1 THEN w.WorkItemNumber ELSE -w.WorkItemNumber END
			""";

	@Query(nativeQuery = true, value = SELECT_ITEM + FROM_ITEM + "WHERE w.WorkItemCode = UUID_TO_BIN(:code) AND w.IsActive = TRUE AND p.IsActive = TRUE")
	Optional<WorkItemEntity> findByCode(@Param("code") String code);

	@Query(nativeQuery = true, value = SELECT_ITEM + FROM_ITEM
			+ "WHERE pa.WorkItemCode = UUID_TO_BIN(:code) AND w.IsActive = TRUE AND p.IsActive = TRUE ORDER BY w.WorkItemNumber")
	List<WorkItemEntity> findChildren(@Param("code") String code);

	@Query(nativeQuery = true, value = SELECT_ITEM + FROM_ITEM + SEARCH_WHERE + ORDER_BY + "LIMIT :limit OFFSET :offset")
	List<WorkItemEntity> search(@Param("hasProject") int hasProject, @Param("project") String project,
			@Param("sprintMode") int sprintMode, @Param("sprint") String sprint, @Param("hasType") int hasType,
			@Param("type") String type, @Param("hasStatus") int hasStatus, @Param("status") String status,
			@Param("hasParent") int hasParent, @Param("parent") String parent, @Param("hasPriority") int hasPriority,
			@Param("priority") String priority, @Param("hasAssignee") int hasAssignee, @Param("assignee") String assignee,
			@Param("hasText") int hasText, @Param("text") String text, @Param("board") int board, @Param("limit") int limit,
			@Param("offset") int offset);

	@Query(nativeQuery = true, value = "SELECT COUNT(*) " + FROM_ITEM + SEARCH_WHERE)
	long countSearch(@Param("hasProject") int hasProject, @Param("project") String project, @Param("sprintMode") int sprintMode,
			@Param("sprint") String sprint, @Param("hasType") int hasType, @Param("type") String type,
			@Param("hasStatus") int hasStatus, @Param("status") String status, @Param("hasParent") int hasParent,
			@Param("parent") String parent, @Param("hasPriority") int hasPriority, @Param("priority") String priority,
			@Param("hasAssignee") int hasAssignee, @Param("assignee") String assignee, @Param("hasText") int hasText,
			@Param("text") String text);

	/**
	 * Inserts nothing (0 rows) if the project is deleted, the initial status is not active, or the creator is not an active
	 * user. {@code parentCode} is the all-zero UUID for no parent. Blank text is stored as NULL.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItem (WorkItemCode, WorkItemNumber, ProjectId, Type, StatusId, Title, Description, AcceptanceCriteria,
			                      Priority, ParentId, EffortPoints, EstimatedHours, RemainingHours, CreatedBy)
			SELECT UUID_TO_BIN(:code), :number, p.ProjectId, :type, st.StatusId, :title, NULLIF(:description, ''),
			       NULLIF(:acceptanceCriteria, ''), :priority,
			       (SELECT pa.WorkItemId FROM WorkItem pa WHERE pa.WorkItemCode = UUID_TO_BIN(:parentCode) AND pa.IsActive = TRUE),
			       :effortPoints, :estimatedHours, :remainingHours, u.UserId
			FROM Project p, WorkItemStatus st, `User` u
			WHERE p.ProjectCode = UUID_TO_BIN(:projectCode) AND p.IsActive = TRUE
			  AND st.ItemType = :type AND st.StatusCode = :status AND st.IsActive = TRUE
			  AND u.UserCode = UUID_TO_BIN(:createdBy) AND u.IsActive = TRUE AND u.DeletedAt IS NULL
			""")
	int insert(@Param("code") String code, @Param("number") long number, @Param("projectCode") String projectCode,
			@Param("type") String type, @Param("status") String status, @Param("title") String title,
			@Param("description") String description, @Param("acceptanceCriteria") String acceptanceCriteria,
			@Param("priority") String priority, @Param("parentCode") String parentCode, @Param("effortPoints") int effortPoints,
			@Param("estimatedHours") BigDecimal estimatedHours, @Param("remainingHours") BigDecimal remainingHours,
			@Param("createdBy") String createdBy);

	/**
	 * A changed priority puts the item in another rank group, so its rank is cleared. MySQL evaluates the assignments of one
	 * {@code UPDATE} left to right: {@code BoardRank} must come before {@code Priority}, which it compares with the old value.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem
			SET Title = :title, Description = NULLIF(:description, ''), AcceptanceCriteria = NULLIF(:acceptanceCriteria, ''),
			    BoardRank = IF(Priority = :priority, BoardRank, NULL), Priority = :priority, EffortPoints = :effortPoints, EstimatedHours = :estimatedHours,
			    RemainingHours = :remainingHours, UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE WorkItemCode = UUID_TO_BIN(:code) AND IsActive = TRUE
			""")
	int updateFields(@Param("code") String code, @Param("title") String title, @Param("description") String description,
			@Param("acceptanceCriteria") String acceptanceCriteria, @Param("priority") String priority,
			@Param("effortPoints") int effortPoints, @Param("estimatedHours") BigDecimal estimatedHours,
			@Param("remainingHours") BigDecimal remainingHours, @Param("updatedBy") String updatedBy);

	/** Matches no row if the parent does not exist or is deleted. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem w
			JOIN WorkItem pa ON pa.WorkItemCode = UUID_TO_BIN(:parentCode) AND pa.IsActive = TRUE
			SET w.ParentId = pa.WorkItemId, w.UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE w.WorkItemCode = UUID_TO_BIN(:code) AND w.IsActive = TRUE
			""")
	int setParent(@Param("code") String code, @Param("parentCode") String parentCode, @Param("updatedBy") String updatedBy);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem SET ParentId = NULL, UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE WorkItemCode = UUID_TO_BIN(:code) AND IsActive = TRUE
			""")
	int clearParent(@Param("code") String code, @Param("updatedBy") String updatedBy);

	/**
	 * Only applies while the item is still in {@code fromStatus} (a status of the item's own type): the guard against two
	 * concurrent moves. Matches no row otherwise, or if the target is not an active status of the item's type.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem w
			JOIN WorkItemStatus cur ON cur.StatusId = w.StatusId AND cur.StatusCode = :fromStatus
			JOIN WorkItemStatus target ON target.ItemType = w.Type AND target.StatusCode = :toStatus AND target.IsActive = TRUE
			SET w.StatusId = target.StatusId, w.BoardRank = NULL, w.UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE w.WorkItemCode = UUID_TO_BIN(:code) AND w.IsActive = TRUE
			""")
	int changeStatus(@Param("code") String code, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
			@Param("updatedBy") String updatedBy);

	/** Matches no row if the sprint does not exist. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem w
			JOIN Sprint s ON s.SprintCode = UUID_TO_BIN(:sprintCode) AND s.IsActive = TRUE
			SET w.SprintId = s.SprintId, w.UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE w.WorkItemCode = UUID_TO_BIN(:code) AND w.IsActive = TRUE
			""")
	int setSprint(@Param("code") String code, @Param("sprintCode") String sprintCode, @Param("updatedBy") String updatedBy);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem SET SprintId = NULL, UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:updatedBy))
			WHERE WorkItemCode = UUID_TO_BIN(:code) AND IsActive = TRUE
			""")
	int clearSprint(@Param("code") String code, @Param("updatedBy") String updatedBy);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem
			SET IsActive = FALSE, DeletedAt = NOW(), UpdatedBy = (SELECT UserId FROM `User` WHERE UserCode = UUID_TO_BIN(:deletedBy))
			WHERE WorkItemCode = UUID_TO_BIN(:code) AND IsActive = TRUE
			""")
	int softDelete(@Param("code") String code, @Param("deletedBy") String deletedBy);

	/**
	 * The codes of the items that share a rank (one project, item type, status and priority), in board order, locked for the
	 * transaction: two reorders of the same group must not renumber it at the same time.
	 */
	@Query(nativeQuery = true, value = """
			SELECT BIN_TO_UUID(w.WorkItemCode)
			FROM WorkItem w
			JOIN Project p ON p.ProjectId = w.ProjectId
			JOIN WorkItemStatus st ON st.StatusId = w.StatusId
			WHERE p.ProjectCode = UUID_TO_BIN(:project) AND w.IsActive = TRUE AND w.Type = :type AND st.StatusCode = :status
			  AND w.Priority = :priority
			ORDER BY w.BoardRank IS NULL, w.BoardRank, w.WorkItemNumber
			FOR UPDATE
			""")
	List<String> lockRankGroup(@Param("project") String project, @Param("type") String type, @Param("status") String status,
			@Param("priority") String priority);

	/**
	 * Sets the rank without touching {@code UpdatedAt}/{@code UpdatedBy}: renumbering the neighbours of a moved card is not a
	 * change of their content. Matches no row if the item already has that rank.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItem SET BoardRank = :rank, UpdatedAt = UpdatedAt
			WHERE WorkItemCode = UUID_TO_BIN(:code) AND IsActive = TRUE AND (BoardRank IS NULL OR BoardRank <> :rank)
			""")
	int setBoardRank(@Param("code") String code, @Param("rank") int rank);

	/** {@code 1} if the candidate is the ancestor itself or anywhere below it (walking ParentId, deleted items included). */
	@Query(nativeQuery = true, value = """
			WITH RECURSIVE subtree (id) AS (
			    SELECT WorkItemId FROM WorkItem WHERE WorkItemCode = UUID_TO_BIN(:ancestor)
			    UNION ALL
			    SELECT c.WorkItemId FROM WorkItem c JOIN subtree ON c.ParentId = subtree.id
			)
			SELECT COUNT(*) FROM subtree JOIN WorkItem w ON w.WorkItemId = subtree.id WHERE w.WorkItemCode = UUID_TO_BIN(:candidate)
			""")
	long countInSubtree(@Param("ancestor") String ancestor, @Param("candidate") String candidate);

	@Query(nativeQuery = true, value = """
			SELECT COUNT(*) FROM WorkItem c JOIN WorkItem pa ON pa.WorkItemId = c.ParentId
			WHERE pa.WorkItemCode = UUID_TO_BIN(:code) AND c.IsActive = TRUE
			""")
	long countActiveChildren(@Param("code") String code);

	/** Reads the {@code SprintMetrics} view: effort points of the PBIs and Bugs in a terminal status. */
	@Query(nativeQuery = true, value = """
			SELECT CAST(COALESCE(SUM(m.CompletedEffort), 0) AS SIGNED)
			FROM SprintMetrics m JOIN Sprint s ON s.SprintId = m.SprintId
			WHERE s.SprintCode = UUID_TO_BIN(:sprintCode)
			""")
	long completedEffortOfSprint(@Param("sprintCode") String sprintCode);

}
