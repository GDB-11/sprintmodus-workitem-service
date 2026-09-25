package com.sprintmodus.workitem_service.domain.model;

import java.util.UUID;

/** A user working on a work item in a role. */
public record Assignment(UUID code, UUID userCode, String fullName, AssignmentRole role) {

	/** {@code Ana Diaz (DEV)}, as audit entries write it. */
	public String label() {
		return fullName + " (" + role + ")";
	}

}
