package com.sprintmodus.workitem_service.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of {@code WorkItemSequence}, the per-project counter work items are numbered from. */
@Entity
@Table(name = "WorkItemSequence")
class WorkItemSequenceEntity {

	@Id
	@Column(name = "SequenceId")
	Long id;

}
