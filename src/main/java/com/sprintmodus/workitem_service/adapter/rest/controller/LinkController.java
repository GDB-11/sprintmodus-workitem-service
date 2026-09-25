package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.Requests;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateLink;
import com.sprintmodus.workitem_service.application.service.CreateLinkUseCase;
import com.sprintmodus.workitem_service.application.service.RemoveLinkUseCase;

/** Relates a work item to another one. The current links are part of the work item's detail. */
@RestController
@RequestMapping("/api/work-items/{workItemCode}/links")
class LinkController {

	private final CreateLinkUseCase createLink;

	private final RemoveLinkUseCase removeLink;

	LinkController(CreateLinkUseCase createLink, RemoveLinkUseCase removeLink) {
		this.createLink = createLink;
		this.removeLink = removeLink;
	}

	@PostMapping
	ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Link request) {
		return createLink.execute(new CreateLink(Actors.from(user), workItemCode, request.targetCode(), request.type()))
				.fold(link -> ResponseEntity.status(HttpStatus.CREATED).body(Responses.Link.from(link)), ErrorMapper::toResponse);
	}

	@DeleteMapping("/{linkCode}")
	ResponseEntity<?> remove(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@PathVariable UUID linkCode) {
		return removeLink.execute(Actors.from(user), workItemCode, linkCode).fold(_ -> ResponseEntity.noContent().build(),
				ErrorMapper::toResponse);
	}

}
