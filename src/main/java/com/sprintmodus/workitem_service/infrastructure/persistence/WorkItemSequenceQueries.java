package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The SQL on {@code WorkItemSequence} (native queries only). {@code NextNumber} is the number the next item of the project
 * gets (a new project starts at 1000). Taking a number is {@code lockNext}, then {@code increment}, in the transaction that
 * inserts the item: the lock makes concurrent creations take distinct, gapless numbers.
 */
interface WorkItemSequenceQueries extends Repository<WorkItemSequenceEntity, Long> {

	@Query(nativeQuery = true, value = """
			SELECT NextNumber FROM WorkItemSequence
			WHERE ProjectId = (SELECT ProjectId FROM Project WHERE ProjectCode = UUID_TO_BIN(:projectCode) AND IsActive = TRUE)
			FOR UPDATE
			""")
	Optional<Long> lockNext(@Param("projectCode") String projectCode);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE WorkItemSequence SET NextNumber = NextNumber + 1
			WHERE ProjectId = (SELECT ProjectId FROM Project WHERE ProjectCode = UUID_TO_BIN(:projectCode))
			""")
	int increment(@Param("projectCode") String projectCode);

	/** For a project that has none yet (project-service normally creates it). Matches no row for a deleted project. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO WorkItemSequence (ProjectId)
			SELECT ProjectId FROM Project WHERE ProjectCode = UUID_TO_BIN(:projectCode) AND IsActive = TRUE
			""")
	int createFor(@Param("projectCode") String projectCode);

}
