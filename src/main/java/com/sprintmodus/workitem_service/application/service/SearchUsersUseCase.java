package com.sprintmodus.workitem_service.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.error.UserSearchError;
import com.sprintmodus.workitem_service.application.error.UserSearchError.InvalidQuery;
import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;
import com.sprintmodus.workitem_service.domain.model.UserRef;

/**
 * The autocomplete of a mention (and, later, of an assignee picker): the active users of the caller's tenant whose name, or
 * any word of it, starts with what was typed. Only code and full name come back, never an e-mail address, and anyone active
 * may be found by anyone active: members already read every work item, so nobody learns something they could not see.
 */
@Service
public class SearchUsersUseCase {

	public static final int DEFAULT_LIMIT = 10;

	public static final int MAX_LIMIT = 50;

	static final int MAX_QUERY_LENGTH = 100;

	private final UserRepository users;

	public SearchUsersUseCase(UserRepository users) {
		this.users = users;
	}

	public Result<List<UserRef>, UserSearchError> execute(String query, int limit) {
		String text = query == null ? "" : query.trim();
		if (text.length() > MAX_QUERY_LENGTH) {
			return Result.failure(new InvalidQuery("The search text cannot be longer than " + MAX_QUERY_LENGTH + " characters."));
		}
		if (limit < 1 || limit > MAX_LIMIT) {
			return Result.failure(new InvalidQuery("The limit must be between 1 and " + MAX_LIMIT + "."));
		}
		return Result.success(users.search(text, limit));
	}

}
