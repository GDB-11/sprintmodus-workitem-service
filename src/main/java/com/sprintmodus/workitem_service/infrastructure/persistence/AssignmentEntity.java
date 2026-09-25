package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AssignmentRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a work item assignment joined with the assigned user. */
@Entity
@Table(name = "WorkItemAssignment")
class AssignmentEntity {

	@Id
	@Column(name = "AssignmentId")
	Long id;

	@Column(name = "WorkItemCode")
	UUID workItemCode;

	@Column(name = "AssignmentCode")
	UUID code;

	@Column(name = "UserCode")
	UUID userCode;

	@Column(name = "FullName")
	String fullName;

	@Enumerated(EnumType.STRING)
	@Column(name = "Role")
	AssignmentRole role;

}
