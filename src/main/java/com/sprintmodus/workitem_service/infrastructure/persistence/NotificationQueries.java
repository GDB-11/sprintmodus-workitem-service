package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every SQL statement on {@code Notification} (native queries only). Every read and write is bound to the recipient's user
 * code, and only notifications about active work items count, so the list, the unread count and "mark as read" always agree.
 */
interface NotificationQueries extends Repository<NotificationEntity, Long> {

	String SELECT_NOTIFICATION = """
			SELECT n.NotificationId, n.NotificationCode, n.Type, w.WorkItemCode, CONCAT(p.`Key`, '-', w.WorkItemNumber) AS WorkItemKey,
			       w.Title AS WorkItemTitle, c.CommentCode, a.UserCode AS ActorCode, a.FullName AS ActorName, n.CreatedAt, n.ReadAt
			FROM Notification n
			JOIN `User` r ON r.UserId = n.UserId
			JOIN WorkItem w ON w.WorkItemId = n.WorkItemId
			JOIN Project p ON p.ProjectId = w.ProjectId
			JOIN `User` a ON a.UserId = n.ActorId
			LEFT JOIN Comment c ON c.CommentId = n.CommentId
			""";

	String OF_RECIPIENT = "WHERE r.UserCode = UUID_TO_BIN(:recipient) AND w.IsActive = TRUE AND p.IsActive = TRUE ";

	@Query(nativeQuery = true, value = SELECT_NOTIFICATION + OF_RECIPIENT + "AND (:unreadOnly = FALSE OR n.IsUnread = TRUE) "
			+ "ORDER BY n.NotificationId DESC LIMIT :limit OFFSET :offset")
	List<NotificationEntity> list(@Param("recipient") String recipient, @Param("unreadOnly") boolean unreadOnly, @Param("limit") int limit,
			@Param("offset") long offset);

	@Query(nativeQuery = true, value = """
			SELECT COUNT(*)
			FROM Notification n
			JOIN `User` r ON r.UserId = n.UserId
			JOIN WorkItem w ON w.WorkItemId = n.WorkItemId
			JOIN Project p ON p.ProjectId = w.ProjectId
			""" + OF_RECIPIENT + "AND (:unreadOnly = FALSE OR n.IsUnread = TRUE)")
	long count(@Param("recipient") String recipient, @Param("unreadOnly") boolean unreadOnly);

	@Query(nativeQuery = true, value = SELECT_NOTIFICATION + OF_RECIPIENT + "AND n.NotificationCode = UUID_TO_BIN(:code)")
	Optional<NotificationEntity> find(@Param("recipient") String recipient, @Param("code") String code);

	/**
	 * Inserts nothing (0 rows) if the recipient, the actor, the item or the comment does not exist or is inactive.
	 * {@code commentCode} may be empty (no comment).
	 */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO Notification (NotificationCode, UserId, Type, WorkItemId, CommentId, ActorId)
			SELECT UUID_TO_BIN(:code), r.UserId, :type, w.WorkItemId, c.CommentId, a.UserId
			FROM `User` r
			JOIN WorkItem w ON w.WorkItemCode = UUID_TO_BIN(:workItemCode) AND w.IsActive = TRUE
			JOIN `User` a ON a.UserCode = UUID_TO_BIN(:actor) AND a.IsActive = TRUE
			LEFT JOIN Comment c ON c.CommentCode = UUID_TO_BIN(NULLIF(:commentCode, '')) AND c.WorkItemId = w.WorkItemId AND c.IsActive = TRUE
			WHERE r.UserCode = UUID_TO_BIN(:recipient) AND r.IsActive = TRUE AND r.DeletedAt IS NULL
			  AND (NULLIF(:commentCode, '') IS NULL OR c.CommentId IS NOT NULL)
			""")
	int insert(@Param("code") String code, @Param("recipient") String recipient, @Param("type") String type,
			@Param("workItemCode") String workItemCode, @Param("commentCode") String commentCode, @Param("actor") String actor);

	/** Keeps the first read time: an already read notification is left alone. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE Notification n
			JOIN `User` r ON r.UserId = n.UserId
			JOIN WorkItem w ON w.WorkItemId = n.WorkItemId
			JOIN Project p ON p.ProjectId = w.ProjectId
			SET n.ReadAt = CURRENT_TIMESTAMP
			WHERE r.UserCode = UUID_TO_BIN(:recipient) AND w.IsActive = TRUE AND p.IsActive = TRUE
			  AND n.NotificationCode = UUID_TO_BIN(:code) AND n.IsUnread = TRUE
			""")
	int markRead(@Param("recipient") String recipient, @Param("code") String code);

	/** Only the notifications that {@code count} and {@code list} show, so the number on the bell drops to exactly zero. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE Notification n
			JOIN `User` r ON r.UserId = n.UserId
			JOIN WorkItem w ON w.WorkItemId = n.WorkItemId
			JOIN Project p ON p.ProjectId = w.ProjectId
			SET n.ReadAt = CURRENT_TIMESTAMP
			WHERE r.UserCode = UUID_TO_BIN(:recipient) AND w.IsActive = TRUE AND p.IsActive = TRUE AND n.IsUnread = TRUE
			""")
	int markAllRead(@Param("recipient") String recipient);

}
