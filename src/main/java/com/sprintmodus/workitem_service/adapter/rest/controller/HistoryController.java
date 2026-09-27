package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.service.ListWorkItemHistoryUseCase;

/** {@code GET} only: the history is written by the use cases that change the item, never by a client. Newest change first. */
@RestController
@RequestMapping("/api/work-items/{workItemCode}/history")
class HistoryController {

	private final ListWorkItemHistoryUseCase listHistory;

	HistoryController(ListWorkItemHistoryUseCase listHistory) {
		this.listHistory = listHistory;
	}

	@GetMapping
	ResponseEntity<?> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return listHistory.execute(Actors.from(user), workItemCode, page, size).fold(history -> ResponseEntity.ok(Responses.History.from(history)),
				ErrorMapper::toResponse);
	}

}
