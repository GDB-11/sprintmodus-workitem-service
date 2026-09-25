package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowTransition;

/** The {@link WorkflowRepository} port over the native queries on the status and transition tables. */
@Repository
class JpaWorkflowRepository implements WorkflowRepository {

	private final WorkflowStatusQueries statuses;

	private final WorkflowTransitionQueries transitions;

	private final StatusUsageQueries usage;

	JpaWorkflowRepository(WorkflowStatusQueries statuses, WorkflowTransitionQueries transitions, StatusUsageQueries usage) {
		this.statuses = statuses;
		this.transitions = transitions;
		this.usage = usage;
	}

	@Override
	public Workflow find(ItemType itemType) {
		return new Workflow(itemType,
				statuses.findByType(itemType.name()).stream()
						.map(entity -> new WorkflowStatus(entity.code, entity.displayName, entity.order, entity.terminal, entity.active)).toList(),
				transitions.findByType(itemType.name()).stream()
						.map(entity -> new WorkflowTransition(entity.from, entity.to, entity.allowedBackward,
								entity.requiredRole == null ? null : AssignmentRole.valueOf(entity.requiredRole)))
						.toList());
	}

	@Override
	public void replace(Workflow workflow) {
		String type = workflow.itemType().name();
		for (WorkflowStatus status : workflow.statuses()) {
			statuses.upsert(status.code(), status.displayName(), type, status.order(), status.terminal());
		}
		statuses.retireExcept(type, workflow.statuses().stream().map(WorkflowStatus::code).toList());
		transitions.deleteByType(type);
		for (WorkflowTransition transition : workflow.transitions()) {
			transitions.insert(type, transition.from(), transition.to(), transition.allowedBackward(),
					transition.requiredRole() == null ? null : transition.requiredRole().name());
		}
	}

	@Override
	public List<StatusUsage> usage(ItemType itemType) {
		return usage.countByStatus(itemType.name()).stream().map(entity -> new StatusUsage(entity.statusCode, (int) entity.items)).toList();
	}

}
