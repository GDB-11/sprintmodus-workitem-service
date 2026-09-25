package com.sprintmodus.workitem_service.domain.model;

/** Something the caller should know about an operation that nevertheless succeeded. */
public record Warning(String code, String message) {

	public static final String NON_STANDARD_HIERARCHY = "NON_STANDARD_HIERARCHY";

	public static final String VELOCITY_NOT_UPDATED = "VELOCITY_NOT_UPDATED";

}
