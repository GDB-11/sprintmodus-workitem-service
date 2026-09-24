package com.sprintmodus.workitem_service.adapter.feign.client;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.sprintmodus.workitem_service.adapter.feign.dto.ProjectDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.SprintDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.VelocityUpdateRequest;

/**
 * Calls to project-service, resolved through Eureka. Sprints and projects belong to project-service: although both
 * services share a tenant database, workitem-service never queries those tables directly.
 * <p>
 * Stub for Phase 3. The endpoints are the ones Phase 4 builds in project-service, and the application layer will
 * reach this client through a {@code ProjectServiceGateway} port (Phase 5). The caller's JWT is forwarded on every
 * call by {@code BearerTokenRelay}.
 */
@FeignClient(name = "project-service")
public interface ProjectServiceClient {

	@GetMapping("/api/projects/{projectCode}")
	ProjectDto getProject(@PathVariable("projectCode") UUID projectCode);

	@GetMapping("/api/sprints/{sprintCode}")
	SprintDto getSprint(@PathVariable("sprintCode") UUID sprintCode);

	@PutMapping("/api/sprints/{sprintCode}/velocity")
	void updateSprintVelocity(@PathVariable("sprintCode") UUID sprintCode, @RequestBody VelocityUpdateRequest request);

}
