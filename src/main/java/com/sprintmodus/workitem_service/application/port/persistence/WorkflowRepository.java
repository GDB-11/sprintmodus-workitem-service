package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.List;

import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Workflow;

public interface WorkflowRepository {

	/** The workflow of an item type, retired statuses included. */
	Workflow find(ItemType itemType);

	/**
	 * Replaces the workflow: statuses are added or updated by code (a retired one comes back), statuses not in the new
	 * workflow are retired, and the transitions are replaced. Statuses are never deleted, since items may still reference them.
	 */
	void replace(Workflow workflow);

	/** The codes of the active statuses of the type that active work items are in, with how many. */
	List<StatusUsage> usage(ItemType itemType);

	record StatusUsage(String statusCode, int items) {
	}

}
