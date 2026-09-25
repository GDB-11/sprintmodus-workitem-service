package com.sprintmodus.workitem_service.adapter.rest.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/** Bodies of the REST requests. Optional fields may be left out of the JSON. */
public final class Requests {

	private Requests() {
	}

	public record CreateWorkItem(UUID projectCode, ItemType type, String title, String description, String acceptanceCriteria,
			Priority priority, UUID parentCode, Integer effortPoints, BigDecimal estimatedHours, BigDecimal remainingHours) {
	}

	/** Fields left out keep their value; a blank description or acceptance criteria clears it. */
	public record UpdateWorkItem(String title, String description, String acceptanceCriteria, Priority priority, Integer effortPoints,
			BigDecimal estimatedHours, BigDecimal remainingHours) {
	}

	public record Parent(UUID parentCode) {
	}

	public record Status(String status) {
	}

	public record Sprint(UUID sprintCode) {
	}

	/** {@code beforeCode} is the card to go in front of; {@code null} sends the card to the end of its group. */
	public record Rank(UUID beforeCode) {
	}

	public record Assignment(UUID userCode, AssignmentRole role) {
	}

	public record Link(UUID targetCode, LinkType type) {
	}

	public record Comment(String content) {
	}

	public record WorkflowStatus(String code, String displayName, Integer order, Boolean isTerminal) {
	}

	public record WorkflowTransition(String from, String to, Boolean allowedBackward, String requiredRole) {
	}

	public record Workflow(ItemType itemType, List<WorkflowStatus> statuses, List<WorkflowTransition> transitions) {
	}

}
