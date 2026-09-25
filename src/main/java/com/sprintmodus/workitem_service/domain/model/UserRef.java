package com.sprintmodus.workitem_service.domain.model;

import java.util.UUID;

/** A tenant user as work items show them. */
public record UserRef(UUID userCode, String fullName) {
}
