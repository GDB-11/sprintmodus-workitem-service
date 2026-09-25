package com.sprintmodus.workitem_service.application.port.external;

import java.util.UUID;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;

/**
 * What work items need from project-service, which owns projects and sprints. Calls carry the caller's own token.
 */
public interface ProjectServiceGateway {

	enum Failure {

		NOT_FOUND,

		/** project-service could not be reached or answered with an error. */
		UNAVAILABLE

	}

	record SprintInfo(UUID sprintCode, UUID projectCode, String name, String status) {

		public boolean isClosed() {
			return "CLOSED".equals(status);
		}

	}

	record ProjectInfo(UUID projectCode, String name) {
	}

	/** {@code NOT_FOUND} if the project does not exist in the caller's tenant. */
	Result<ProjectInfo, Failure> getProject(UUID projectCode);

	Result<SprintInfo, Failure> getSprint(UUID sprintCode);

	/**
	 * Reports the effort points completed in a sprint. A closed sprint's velocity is frozen: project-service refuses the
	 * update and this still counts as success, as there is nothing to do.
	 */
	Result<Unit, Failure> updateSprintVelocity(UUID sprintCode, int velocity);

}
