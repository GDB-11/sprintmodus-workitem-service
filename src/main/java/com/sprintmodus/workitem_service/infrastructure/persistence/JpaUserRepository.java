package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;
import com.sprintmodus.workitem_service.domain.model.UserRef;

@Repository
class JpaUserRepository implements UserRepository {

	private final UserQueries users;

	JpaUserRepository(UserQueries users) {
		this.users = users;
	}

	@Override
	public List<UserRef> findActive(Collection<UUID> userCodes) {
		if (userCodes.isEmpty()) {
			return List.of();
		}
		String codes = userCodes.stream().map(code -> "\"" + code + "\"").collect(Collectors.joining(",", "[", "]"));
		return users.findActive(codes).stream().map(JpaUserRepository::toDomain).toList();
	}

	@Override
	public List<UserRef> search(String query, int limit) {
		String escaped = escapeLike(query);
		return users.search(escaped + "%", "% " + escaped + "%", limit).stream().map(JpaUserRepository::toDomain).toList();
	}

	@Override
	public int countActive() {
		return users.countActive();
	}

	/** {@code %}, {@code _} and the escape character itself lose their meaning, so a typed {@code %} finds a literal one. */
	static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static UserRef toDomain(UserEntity entity) {
		return new UserRef(entity.code, entity.fullName);
	}

}
