package com.sprintmodus.workitem_service.application.service;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkflowResponse;
import com.sprintmodus.workitem_service.application.error.WorkflowError;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.ItemType;

/** The workflow configured for an item type: its active statuses in order, and the legal transitions. Drives status dropdowns and Kanban columns. */
@Service
public class GetWorkflowUseCase {

	private final WorkflowRepository workflows;

	public GetWorkflowUseCase(WorkflowRepository workflows) {
		this.workflows = workflows;
	}

	public Result<WorkflowResponse, WorkflowError> execute(ItemType itemType) {
		return Result.success(WorkflowResponse.from(workflows.find(itemType)));
	}

}
