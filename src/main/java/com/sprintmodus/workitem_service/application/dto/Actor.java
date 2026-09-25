package com.sprintmodus.workitem_service.application.dto;

import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.OrganizationRole;

/** Who is performing a use case, taken from the verified JWT. */
public record Actor(UUID userCode, OrganizationRole role) {

	public boolean canAdminister() {
		return role.canAdminister();
	}

}
