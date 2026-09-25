package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code WorkItemLink} (native queries only). Unlinking deactivates the row. */
interface LinkQueries extends Repository<LinkEntity, Long> {

	String SELECT_LINK = """
			SELECT l.LinkId, l.LinkCode, l.LinkType,
			       CASE WHEN sw.WorkItemCode = UUID_TO_BIN(:workItemCode) THEN tw.WorkItemCode ELSE sw.WorkItemCode END AS OtherCode
			FROM WorkItemLink l
			JOIN WorkItem sw ON sw.WorkItemId = l.SourceItemId
			JOIN WorkItem tw ON tw.WorkItemId = l.TargetItemId
			""";

	@Query(nativeQuery = true, value = SELECT_LINK + """
			WHERE (sw.WorkItemCode = UUID_TO_BIN(:workItemCode) OR tw.WorkItemCode = UUID_TO_BIN(:workItemCode))
			  AND l.IsActive = TRUE AND sw.IsActive = TRUE AND tw.IsActive = TRUE
			ORDER BY l.LinkId
			""")
	List<LinkEntity> findByWorkItem(@Param("workItemCode") String workItemCode);

	@Query(nativeQuery = true, value = SELECT_LINK + """
			WHERE l.LinkCode = UUID_TO_BIN(:linkCode)
			  AND (sw.WorkItemCode = UUID_TO_BIN(:workItemCode) OR tw.WorkItemCode = UUID_TO_BIN(:workItemCode))
			  AND l.IsActive = TRUE AND sw.IsActive = TRUE AND tw.IsActive = TRUE
			""")
	Optional<LinkEntity> find(@Param("workItemCode") String workItemCode, @Param("linkCode") String linkCode);

	/** The active link of this exact type from source to target, if any (source's "other" is always the target here). */
	@Query(nativeQuery = true, value = """
			SELECT l.LinkId, l.LinkCode, l.LinkType, tw.WorkItemCode AS OtherCode
			FROM WorkItemLink l
			JOIN WorkItem sw ON sw.WorkItemId = l.SourceItemId
			JOIN WorkItem tw ON tw.WorkItemId = l.TargetItemId
			WHERE sw.WorkItemCode = UUID_TO_BIN(:sourceCode) AND tw.WorkItemCode = UUID_TO_BIN(:targetCode)
			  AND l.LinkType = :type AND l.IsActive = TRUE
			""")
	Optional<LinkEntity> findActive(@Param("sourceCode") String sourceCode, @Param("targetCode") String targetCode,
			@Param("type") String type);

	/**
	 * Whether {@code source} is already reachable from {@code target} by following this type's active links forward:
	 * if so, adding {@code source -> target} would close a cycle. The graph is acyclic before every insert (each one is
	 * checked the same way), so the walk always terminates.
	 */
	@Query(nativeQuery = true, value = """
			WITH RECURSIVE reachable (id) AS (
			    SELECT TargetItemId FROM WorkItemLink
			    WHERE SourceItemId = (SELECT WorkItemId FROM WorkItem WHERE WorkItemCode = UUID_TO_BIN(:targetCode))
			      AND LinkType = :type AND IsActive = TRUE
			    UNION ALL
			    SELECT l.TargetItemId FROM WorkItemLink l JOIN reachable r ON l.SourceItemId = r.id
			    WHERE l.LinkType = :type AND l.IsActive = TRUE
			)
			SELECT COUNT(*) FROM reachable JOIN WorkItem w ON w.WorkItemId = reachable.id
			WHERE w.WorkItemCode = UUID_TO_BIN(:sourceCode)
			""")
	long countCyclePath(@Param("sourceCode") String sourceCode, @Param("targetCode") String targetCode, @Param("type") String type);

	/**
	 * Links an active source to an active target. If the same source, target and type were linked before and removed,
	 * that row is reactivated (the unique key covers inactive rows too). Inserts nothing (0 rows) if either item does
	 * not exist or is deleted.
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemLink (LinkCode, SourceItemId, TargetItemId, LinkType, CreatedBy)
			SELECT UUID_TO_BIN(:code), sw.WorkItemId, tw.WorkItemId, :type, u.UserId
			FROM WorkItem sw, WorkItem tw, `User` u
			WHERE sw.WorkItemCode = UUID_TO_BIN(:sourceCode) AND sw.IsActive = TRUE
			  AND tw.WorkItemCode = UUID_TO_BIN(:targetCode) AND tw.IsActive = TRUE
			  AND u.UserCode = UUID_TO_BIN(:createdBy) AND u.IsActive = TRUE AND u.DeletedAt IS NULL
			ON DUPLICATE KEY UPDATE IsActive = TRUE, CreatedBy = VALUES(CreatedBy)
			""")
	int create(@Param("code") String code, @Param("sourceCode") String sourceCode, @Param("targetCode") String targetCode,
			@Param("type") String type, @Param("createdBy") String createdBy);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItemLink l
			JOIN WorkItem sw ON sw.WorkItemId = l.SourceItemId
			JOIN WorkItem tw ON tw.WorkItemId = l.TargetItemId
			SET l.IsActive = FALSE
			WHERE l.LinkCode = UUID_TO_BIN(:linkCode)
			  AND (sw.WorkItemCode = UUID_TO_BIN(:workItemCode) OR tw.WorkItemCode = UUID_TO_BIN(:workItemCode)) AND l.IsActive = TRUE
			""")
	int unlink(@Param("workItemCode") String workItemCode, @Param("linkCode") String linkCode);

}
