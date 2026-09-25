package com.sprintmodus.workitem_service.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of {@code WorkItemAudit}. The history is append-only: it is only written, through {@link WorkItemAuditQueries}. */
@Entity
@Table(name = "WorkItemAudit")
class WorkItemAuditEntity {

	@Id
	@Column(name = "AuditId")
	Long id;

}
