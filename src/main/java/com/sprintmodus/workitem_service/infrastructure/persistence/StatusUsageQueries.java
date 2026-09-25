package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Counts (native query only). */
interface StatusUsageQueries extends Repository<StatusUsageEntity, String> {

	@Query(nativeQuery = true, value = """
			SELECT st.StatusCode AS StatusCode, COUNT(*) AS Items
			FROM WorkItem w JOIN WorkItemStatus st ON st.StatusId = w.StatusId
			WHERE w.IsActive = TRUE AND st.ItemType = :itemType AND st.IsActive = TRUE
			GROUP BY st.StatusCode
			""")
	List<StatusUsageEntity> countByStatus(@Param("itemType") String itemType);

}
