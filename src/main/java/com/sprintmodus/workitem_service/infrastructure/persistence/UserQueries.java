package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every SQL statement this service runs on {@code User} (native queries only, reads only: auth-service owns users). */
interface UserQueries extends Repository<UserEntity, Long> {

	/** {@code codes} is a JSON array of user UUIDs. */
	@Query(nativeQuery = true, value = """
			SELECT u.UserId, u.UserCode, u.FullName
			FROM `User` u
			JOIN JSON_TABLE(:codes, '$[*]' COLUMNS (code CHAR(36) PATH '$')) j ON u.UserCode = UUID_TO_BIN(j.code)
			WHERE u.IsActive = TRUE AND u.DeletedAt IS NULL
			""")
	List<UserEntity> findActive(@Param("codes") String codes);

	/**
	 * {@code prefix} is {@code text%} and {@code wordPrefix} is {@code % text%}, both already escaped for {@code LIKE}: the name
	 * or any word of it starts with the text. The collation ignores case and accents.
	 */
	@Query(nativeQuery = true, value = """
			SELECT u.UserId, u.UserCode, u.FullName
			FROM `User` u
			WHERE u.IsActive = TRUE AND u.DeletedAt IS NULL AND (u.FullName LIKE :prefix OR u.FullName LIKE :wordPrefix)
			ORDER BY u.FullName, u.UserId
			LIMIT :limit
			""")
	List<UserEntity> search(@Param("prefix") String prefix, @Param("wordPrefix") String wordPrefix, @Param("limit") int limit);

	@Query(nativeQuery = true, value = """
			SELECT u.UserId, u.UserCode, u.FullName
			FROM `User` u
			WHERE u.IsActive = TRUE AND u.DeletedAt IS NULL AND u.Email = :email
			LIMIT 1
			""")
	List<UserEntity> activeWithEmail(@Param("email") String email);

	@Query(nativeQuery = true, value = """
			SELECT u.UserId, u.UserCode, u.FullName
			FROM `User` u
			WHERE u.IsActive = TRUE AND u.DeletedAt IS NULL AND u.FullName = :fullName
			ORDER BY u.UserId
			LIMIT 2
			""")
	List<UserEntity> activeNamed(@Param("fullName") String fullName);

	@Query(nativeQuery = true, value = """
			SELECT COUNT(*)
			FROM `User` u
			WHERE u.IsActive = TRUE AND u.DeletedAt IS NULL
			""")
	int countActive();

}
