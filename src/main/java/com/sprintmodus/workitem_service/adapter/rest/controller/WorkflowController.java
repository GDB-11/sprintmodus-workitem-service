package com.sprintmodus.workitem_service.adapter.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.application.service.GetWorkflowUseCase;
import com.sprintmodus.workitem_service.domain.model.ItemType;

/** The workflow configured for an item type; clients build status dropdowns and Kanban columns from it. */
@RestController
@RequestMapping("/api/status-workflows")
class WorkflowController {

	private final GetWorkflowUseCase getWorkflow;

	WorkflowController(GetWorkflowUseCase getWorkflow) {
		this.getWorkflow = getWorkflow;
	}

	@GetMapping("/{itemType}")
	ResponseEntity<?> get(@PathVariable ItemType itemType) {
		return getWorkflow.execute(itemType).fold(workflow -> ResponseEntity.ok(Responses.Workflow.from(workflow)), ErrorMapper::toResponse);
	}

}
