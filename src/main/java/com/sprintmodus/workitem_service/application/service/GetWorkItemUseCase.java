package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Responses.WorkItemResponse;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;

@Service
public class GetWorkItemUseCase {

	private final WorkItemDetails details;

	public GetWorkItemUseCase(WorkItemDetails details) {
		this.details = details;
	}

	public Result<WorkItemResponse, WorkItemError> execute(UUID workItemCode) {
		return details.load(workItemCode, List.of()).<Result<WorkItemResponse, WorkItemError>>map(Result::success)
				.orElseGet(() -> Result.failure(new WorkItemNotFound()));
	}

}
