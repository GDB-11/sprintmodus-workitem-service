package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement on {@code Comment} (native queries only). */
interface CommentQueries extends Repository<CommentEntity, Long> {

	String SELECT_COMMENT = """
			SELECT c.CommentId, c.CommentCode, w.WorkItemCode, u.UserCode AS AuthorCode, u.FullName AS AuthorName, c.Content, c.CreatedAt
			FROM Comment c
			JOIN WorkItem w ON w.WorkItemId = c.WorkItemId
			JOIN `User` u ON u.UserId = c.UserId
			""";

	@Query(nativeQuery = true, value = SELECT_COMMENT
			+ "WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND w.IsActive = TRUE AND c.IsActive = TRUE ORDER BY c.CreatedAt, c.CommentId")
	List<CommentEntity> findByWorkItem(@Param("workItemCode") String workItemCode);

	@Query(nativeQuery = true, value = SELECT_COMMENT + "WHERE c.CommentCode = UUID_TO_BIN(:code) AND c.IsActive = TRUE")
	Optional<CommentEntity> findByCode(@Param("code") String code);

	/** Inserts nothing (0 rows) if the item is deleted or the author is not an active user. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO Comment (CommentCode, WorkItemId, UserId, Content)
			SELECT UUID_TO_BIN(:code), w.WorkItemId, u.UserId, :content
			FROM WorkItem w, `User` u
			WHERE w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND w.IsActive = TRUE
			  AND u.UserCode = UUID_TO_BIN(:authorCode) AND u.IsActive = TRUE AND u.DeletedAt IS NULL
			""")
	int insert(@Param("code") String code, @Param("workItemCode") String workItemCode, @Param("authorCode") String authorCode,
			@Param("content") String content);

}
