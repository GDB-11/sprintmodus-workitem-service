package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;

public interface AssignmentRepository {

	List<Assignment> findByWorkItem(UUID workItemCode);

	/** The assignees of several items at once (a list needs them for every row); items nobody works on are absent. */
	java.util.Map<UUID, List<Assignment>> findByWorkItems(java.util.Collection<UUID> workItemCodes);

	Optional<Assignment> find(UUID workItemCode, UUID assignmentCode);

	boolean isAssigned(UUID workItemCode, UUID userCode, AssignmentRole role);

	/**
	 * Assigns an active tenant user (assigning again after an unassign reactivates the old row). Empty if the work item is
	 * not active or the user does not exist.
	 */
	Optional<Assignment> assign(UUID workItemCode, UUID userCode, AssignmentRole role);

	boolean unassign(UUID workItemCode, UUID assignmentCode);

}
