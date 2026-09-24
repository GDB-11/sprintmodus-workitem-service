package com.sprintmodus.workitem_service.adapter.feign.dto;

import java.time.LocalDate;
import java.util.UUID;

/** A sprint as project-service returns it. workitem-service never reads the Sprint table itself. */
public record SprintDto(UUID sprintCode, UUID projectCode, String name, String status, LocalDate startDate,
		LocalDate endDate, int velocity) {
}
