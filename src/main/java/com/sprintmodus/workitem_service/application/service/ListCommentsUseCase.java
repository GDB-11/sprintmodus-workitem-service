package com.sprintmodus.workitem_service.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Responses.CommentResponse;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.CommentError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;

@Service
public class ListCommentsUseCase {

	private final WorkItemRepository items;

	private final CommentRepository comments;

	public ListCommentsUseCase(WorkItemRepository items, CommentRepository comments) {
		this.items = items;
		this.comments = comments;
	}

	public Result<List<CommentResponse>, CommentError> execute(UUID workItemCode) {
		if (items.findByCode(workItemCode).isEmpty()) {
			return Result.failure(new WorkItemNotFound());
		}
		return Result.success(comments.findByWorkItem(workItemCode).stream().map(CommentResponse::from).toList());
	}

}
