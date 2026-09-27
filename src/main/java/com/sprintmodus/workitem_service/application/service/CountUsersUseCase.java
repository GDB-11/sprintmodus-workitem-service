package com.sprintmodus.workitem_service.application.service;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.error.UserSearchError;
import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;

/** How many active users the caller's tenant has: the plan meter's "usuarios usados", never a made-up number. */
@Service
public class CountUsersUseCase {

	private final UserRepository users;

	public CountUsersUseCase(UserRepository users) {
		this.users = users;
	}

	public Result<Integer, UserSearchError> execute() {
		return Result.success(users.countActive());
	}

}
