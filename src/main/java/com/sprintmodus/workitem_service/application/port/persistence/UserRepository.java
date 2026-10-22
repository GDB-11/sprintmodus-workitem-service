package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.UserRef;

/**
 * The users of the current tenant, as much as this service needs: who exists (to validate a mention) and who matches what
 * someone typed (to autocomplete one). Users belong to auth-service; this is a read-only view of the tenant database, and
 * only active users are ever returned. Never an e-mail address.
 */
public interface UserRepository {

	/** The active users among {@code userCodes}; codes that are unknown or inactive are simply absent. */
	List<UserRef> findActive(Collection<UUID> userCodes);

	/** Active users whose full name, or any word of it, starts with {@code query} (blank matches everyone), by name. */
	List<UserRef> search(String query, int limit);

	/** The active user with this e-mail address (any case), if there is one. The address itself is never returned. */
	java.util.Optional<UserRef> findByEmail(String email);

	/** The active users whose full name is exactly this (ignoring case and accents); at most 2, enough to tell it is ambiguous. */
	List<UserRef> findByFullName(String fullName);

	/** How many active users the caller's tenant has (the plan meter's "usuarios usados"). */
	int countActive();

}
