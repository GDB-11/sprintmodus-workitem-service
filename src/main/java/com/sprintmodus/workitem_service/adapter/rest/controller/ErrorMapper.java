package com.sprintmodus.workitem_service.adapter.rest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sprintmodus.common_lib.result.ApplicationError;
import com.sprintmodus.common_lib.web.ErrorResponse;
import com.sprintmodus.workitem_service.application.error.AssignmentError;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.LinkError;
import com.sprintmodus.workitem_service.application.error.SprintMoveError;
import com.sprintmodus.workitem_service.application.error.StatusTransitionError;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkflowError;

/**
 * The HTTP status of every business error. The switches are exhaustive over the sealed hierarchies, so adding an error
 * type without deciding its status does not compile. Messages never carry tenant or user details.
 */
final class ErrorMapper {

	private ErrorMapper() {
	}

	@SuppressWarnings("unchecked")
	static <T> ResponseEntity<T> toResponse(ApplicationError error) {
		return (ResponseEntity<T>) ResponseEntity.status(status(error)).body(new ErrorResponse(error.code(), error.message()));
	}

	static HttpStatus status(ApplicationError error) {
		return switch (error) {
			case WorkItemError workItem -> status(workItem);
			case StatusTransitionError transition -> status(transition);
			case AssignmentError assignment -> status(assignment);
			case LinkError link -> status(link);
			case CommentError comment -> status(comment);
			case SprintMoveError move -> status(move);
			case WorkflowError workflow -> status(workflow);
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	private static HttpStatus status(WorkItemError error) {
		return switch (error) {
			case WorkItemError.InvalidWorkItemData _, WorkItemError.InvalidParent _ -> HttpStatus.BAD_REQUEST;
			case WorkItemError.WorkItemNotFound _, WorkItemError.ProjectNotFound _ -> HttpStatus.NOT_FOUND;
			case WorkItemError.HasChildren _, WorkItemError.WorkflowNotConfigured _ -> HttpStatus.CONFLICT;
			case WorkItemError.NotAllowed _ -> HttpStatus.FORBIDDEN;
		};
	}

	private static HttpStatus status(StatusTransitionError error) {
		return switch (error) {
			case StatusTransitionError.WorkItemNotFound _ -> HttpStatus.NOT_FOUND;
			case StatusTransitionError.UnknownStatus _ -> HttpStatus.BAD_REQUEST;
			case StatusTransitionError.InvalidTransition _, StatusTransitionError.StatusChangedConcurrently _ -> HttpStatus.CONFLICT;
			case StatusTransitionError.NotAllowed _ -> HttpStatus.FORBIDDEN;
		};
	}

	private static HttpStatus status(AssignmentError error) {
		return switch (error) {
			case AssignmentError.InvalidAssignment _ -> HttpStatus.BAD_REQUEST;
			case AssignmentError.WorkItemNotFound _, AssignmentError.UserNotFound _, AssignmentError.AssignmentNotFound _ -> HttpStatus.NOT_FOUND;
			case AssignmentError.AlreadyAssigned _ -> HttpStatus.CONFLICT;
		};
	}

	private static HttpStatus status(LinkError error) {
		return switch (error) {
			case LinkError.InvalidLink _ -> HttpStatus.BAD_REQUEST;
			case LinkError.WorkItemNotFound _, LinkError.TargetNotFound _, LinkError.LinkNotFound _ -> HttpStatus.NOT_FOUND;
			case LinkError.AlreadyLinked _, LinkError.CyclicLink _ -> HttpStatus.CONFLICT;
		};
	}

	private static HttpStatus status(CommentError error) {
		return switch (error) {
			case CommentError.InvalidComment _ -> HttpStatus.BAD_REQUEST;
			case CommentError.WorkItemNotFound _ -> HttpStatus.NOT_FOUND;
		};
	}

	private static HttpStatus status(SprintMoveError error) {
		return switch (error) {
			case SprintMoveError.WorkItemNotFound _, SprintMoveError.SprintNotFound _ -> HttpStatus.NOT_FOUND;
			case SprintMoveError.SprintClosed _, SprintMoveError.SprintOfAnotherProject _ -> HttpStatus.CONFLICT;
			case SprintMoveError.ProjectServiceUnavailable _ -> HttpStatus.SERVICE_UNAVAILABLE;
			case SprintMoveError.NotAllowed _ -> HttpStatus.FORBIDDEN;
		};
	}

	private static HttpStatus status(WorkflowError error) {
		return switch (error) {
			case WorkflowError.InvalidWorkflow _ -> HttpStatus.BAD_REQUEST;
			case WorkflowError.NotAllowed _ -> HttpStatus.FORBIDDEN;
			case WorkflowError.StatusInUse _ -> HttpStatus.CONFLICT;
		};
	}

}
