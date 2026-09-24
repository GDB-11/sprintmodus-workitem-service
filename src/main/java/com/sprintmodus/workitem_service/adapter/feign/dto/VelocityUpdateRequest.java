package com.sprintmodus.workitem_service.adapter.feign.dto;

/** Effort points completed in a sprint. workitem-service computes it (it owns the items) and project-service stores it. */
public record VelocityUpdateRequest(int velocity) {
}
