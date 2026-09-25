package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every SQL statement on {@code WorkItemStatus} (native queries only). Statuses are never deleted (work items reference
 * them): one that leaves a workflow is retired with {@code IsActive = FALSE}, and comes back if it returns.
 */
interface WorkflowStatusQueries extends Repository<WorkflowStatusEntity, Long> {

	@Query(nativeQuery = true, value = """
			SELECT StatusId, StatusCode, DisplayName, ItemType, SortOrder, IsTerminal, IsActive
			FROM WorkItemStatus WHERE ItemType = :itemType ORDER BY SortOrder, StatusId
			""")
	List<WorkflowStatusEntity> findByType(@Param("itemType") String itemType);

	/** Adds a status or updates the one with the same type and code, reactivating it. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemStatus (StatusCode, DisplayName, ItemType, SortOrder, IsTerminal, IsActive)
			VALUES (:code, :displayName, :itemType, :sortOrder, :terminal, TRUE) AS incoming
			ON DUPLICATE KEY UPDATE DisplayName = incoming.DisplayName, SortOrder = incoming.SortOrder,
			                        IsTerminal = incoming.IsTerminal, IsActive = TRUE
			""")
	int upsert(@Param("code") String code, @Param("displayName") String displayName, @Param("itemType") String itemType,
			@Param("sortOrder") int sortOrder, @Param("terminal") boolean terminal);

	/** Retires every active status of the type whose code is not in {@code keptCodes}. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = "UPDATE WorkItemStatus SET IsActive = FALSE WHERE ItemType = :itemType AND StatusCode NOT IN (:keptCodes)")
	int retireExcept(@Param("itemType") String itemType, @Param("keptCodes") Collection<String> keptCodes);

}
