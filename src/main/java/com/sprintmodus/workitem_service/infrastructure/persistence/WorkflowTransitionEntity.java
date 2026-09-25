package com.sprintmodus.workitem_service.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a row of {@code WorkItemStatusTransition}, with its two statuses named by code. */
@Entity
@Table(name = "WorkItemStatusTransition")
class WorkflowTransitionEntity {

	@Id
	@Column(name = "TransitionId")
	Long id;

	@Column(name = "FromCode")
	String from;

	@Column(name = "ToCode")
	String to;

	@Column(name = "AllowedBackward")
	boolean allowedBackward;

	@Column(name = "RequiredRole")
	String requiredRole;

}
