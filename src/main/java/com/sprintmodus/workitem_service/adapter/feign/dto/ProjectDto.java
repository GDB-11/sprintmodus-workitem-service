package com.sprintmodus.workitem_service.adapter.feign.dto;

import java.util.UUID;

/** A project as project-service returns it. Only what work items need. */
public record ProjectDto(UUID projectCode, String name, String key) {
}
