package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code WorkItemStatusTransition} (native queries only). */
interface WorkflowTransitionQueries extends Repository<WorkflowTransitionEntity, Long> {

	@Query(nativeQuery = true, value = """
			SELECT tr.TransitionId, f.StatusCode AS FromCode, t.StatusCode AS ToCode, tr.AllowedBackward, tr.RequiredRole
			FROM WorkItemStatusTransition tr
			JOIN WorkItemStatus f ON f.StatusId = tr.FromStatusId
			JOIN WorkItemStatus t ON t.StatusId = tr.ToStatusId
			WHERE tr.ItemType = :itemType ORDER BY tr.TransitionId
			""")
	List<WorkflowTransitionEntity> findByType(@Param("itemType") String itemType);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = "DELETE FROM WorkItemStatusTransition WHERE ItemType = :itemType")
	int deleteByType(@Param("itemType") String itemType);

	/** Both statuses are looked up by code within the type; nothing is inserted if either does not exist. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemStatusTransition (ItemType, FromStatusId, ToStatusId, AllowedBackward, RequiredRole)
			SELECT :itemType, f.StatusId, t.StatusId, :allowedBackward, :requiredRole
			FROM WorkItemStatus f, WorkItemStatus t
			WHERE f.ItemType = :itemType AND f.StatusCode = :fromCode AND t.ItemType = :itemType AND t.StatusCode = :toCode
			""")
	int insert(@Param("itemType") String itemType, @Param("fromCode") String fromCode, @Param("toCode") String toCode,
			@Param("allowedBackward") boolean allowedBackward, @Param("requiredRole") String requiredRole);

}
