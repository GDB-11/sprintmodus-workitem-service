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
import com.sprintmodus.workitem_service.application.dto.Commands.Assign;
import com.sprintmodus.workitem_service.application.service.AssignWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.UnassignWorkItemUseCase;

/** Assigns users to a work item in a role. The current assignees are part of the work item's detail. */
@RestController
@RequestMapping("/api/work-items/{workItemCode}/assignments")
class AssignmentController {

	private final AssignWorkItemUseCase assign;

	private final UnassignWorkItemUseCase unassign;

	AssignmentController(AssignWorkItemUseCase assign, UnassignWorkItemUseCase unassign) {
		this.assign = assign;
		this.unassign = unassign;
	}

	@PostMapping
	ResponseEntity<?> assign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Assignment request) {
		return assign.execute(new Assign(Actors.from(user), workItemCode, request.userCode(), request.role()))
				.fold(assignee -> ResponseEntity.status(HttpStatus.CREATED).body(Responses.Assignee.from(assignee)), ErrorMapper::toResponse);
	}

	@DeleteMapping("/{assignmentCode}")
	ResponseEntity<?> unassign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@PathVariable UUID assignmentCode) {
		return unassign.execute(Actors.from(user), workItemCode, assignmentCode).fold(_ -> ResponseEntity.noContent().build(),
				ErrorMapper::toResponse);
	}

}
