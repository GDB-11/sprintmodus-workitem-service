package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.Requests;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.dto.Commands.UpsertWorkflow;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkflowStatusDefinition;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkflowTransitionDefinition;
import com.sprintmodus.workitem_service.application.service.UpsertWorkflowUseCase;

/** Customizing a tenant's workflow: owners and admins only. */
@RestController
@RequestMapping("/api/admin/status-workflows")
class AdminWorkflowController {

	private final UpsertWorkflowUseCase upsertWorkflow;

	AdminWorkflowController(UpsertWorkflowUseCase upsertWorkflow) {
		this.upsertWorkflow = upsertWorkflow;
	}

	/** Replaces the whole workflow of {@code itemType}: its statuses and its transitions. */
	@PostMapping
	ResponseEntity<?> upsert(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Requests.Workflow request) {
		List<WorkflowStatusDefinition> statuses = request.statuses() == null ? null
				: request.statuses().stream().map(s -> new WorkflowStatusDefinition(s.code(), s.displayName(), s.order(), s.isTerminal())).toList();
		List<WorkflowTransitionDefinition> transitions = request.transitions() == null ? null
				: request.transitions().stream()
						.map(t -> new WorkflowTransitionDefinition(t.from(), t.to(), t.allowedBackward(), t.requiredRole())).toList();
		return upsertWorkflow.execute(new UpsertWorkflow(Actors.from(user), request.itemType(), statuses, transitions))
				.fold(workflow -> ResponseEntity.ok(Responses.Workflow.from(workflow)), ErrorMapper::toResponse);
	}

}
