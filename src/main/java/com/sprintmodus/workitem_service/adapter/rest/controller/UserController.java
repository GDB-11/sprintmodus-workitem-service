package com.sprintmodus.workitem_service.adapter.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.service.CountUsersUseCase;
import com.sprintmodus.workitem_service.application.service.SearchUsersUseCase;

/** {@code GET} only: users are created and changed by auth-service. Only code and name are exposed. */
@RestController
@RequestMapping("/api/users")
class UserController {

	private final SearchUsersUseCase searchUsers;

	private final CountUsersUseCase countUsers;

	UserController(SearchUsersUseCase searchUsers, CountUsersUseCase countUsers) {
		this.searchUsers = searchUsers;
		this.countUsers = countUsers;
	}

	@GetMapping
	ResponseEntity<?> search(@RequestParam(name = "q", defaultValue = "") String query,
			@RequestParam(defaultValue = "" + SearchUsersUseCase.DEFAULT_LIMIT) int limit) {
		return searchUsers.execute(query, limit).fold(found -> ResponseEntity.ok(found.stream().map(Responses.User::from).toList()),
				ErrorMapper::toResponse);
	}

	/** The number of active users of the caller's tenant; {@code maxUsers} is already in the JWT. */
	@GetMapping("/count")
	ResponseEntity<?> count() {
		return countUsers.execute().fold(count -> ResponseEntity.ok(new Responses.UserCount(count)), ErrorMapper::toResponse);
	}

}
