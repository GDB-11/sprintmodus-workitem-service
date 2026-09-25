package com.sprintmodus.workitem_service.adapter.feign;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.adapter.feign.client.ProjectServiceClient;
import com.sprintmodus.workitem_service.adapter.feign.dto.ProjectDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.SprintDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.VelocityUpdateRequest;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;

import feign.FeignException;

/**
 * The {@link ProjectServiceGateway} port over the Feign client. A 404 means the sprint (or project) does not exist; anything else that
 * goes wrong (an error status, no instance in Eureka, a timeout) is reported as "unavailable", never as an exception, so use
 * cases decide what it means for them.
 */
@Component
class ProjectServiceGatewayImpl implements ProjectServiceGateway {

	private static final Logger log = LoggerFactory.getLogger(ProjectServiceGatewayImpl.class);

	private final ProjectServiceClient client;

	ProjectServiceGatewayImpl(ProjectServiceClient client) {
		this.client = client;
	}

	@Override
	public Result<ProjectInfo, Failure> getProject(UUID projectCode) {
		try {
			ProjectDto project = client.getProject(projectCode);
			return Result.success(new ProjectInfo(project.projectCode(), project.name()));
		}
		catch (FeignException.NotFound e) {
			return Result.failure(Failure.NOT_FOUND);
		}
		catch (RuntimeException e) {
			log.warn("project-service could not provide project {}: {}", projectCode, e.getMessage());
			return Result.failure(Failure.UNAVAILABLE);
		}
	}

	@Override
	public Result<SprintInfo, Failure> getSprint(UUID sprintCode) {
		try {
			SprintDto sprint = client.getSprint(sprintCode);
			return Result.success(new SprintInfo(sprint.sprintCode(), sprint.projectCode(), sprint.name(), sprint.status()));
		}
		catch (FeignException.NotFound e) {
			return Result.failure(Failure.NOT_FOUND);
		}
		catch (RuntimeException e) {
			log.warn("project-service could not provide sprint {}: {}", sprintCode, e.getMessage());
			return Result.failure(Failure.UNAVAILABLE);
		}
	}

	@Override
	public Result<Unit, Failure> updateSprintVelocity(UUID sprintCode, int velocity) {
		try {
			client.updateSprintVelocity(sprintCode, new VelocityUpdateRequest(velocity));
			return Result.success(Unit.VALUE);
		}
		catch (FeignException.Conflict e) {
			// the sprint is closed: its velocity is frozen, so there is nothing to update
			return Result.success(Unit.VALUE);
		}
		catch (RuntimeException e) {
			log.warn("project-service did not accept the velocity of sprint {}: {}", sprintCode, e.getMessage());
			return Result.failure(Failure.UNAVAILABLE);
		}
	}

}
