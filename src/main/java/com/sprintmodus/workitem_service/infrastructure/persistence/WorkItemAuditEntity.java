package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of {@code WorkItemAudit}, joined with the user who made the change. The history is append-only: it is written and read through {@link WorkItemAuditQueries}. */
@Entity
@Table(name = "WorkItemAudit")
class WorkItemAuditEntity {

	@Id
	@Column(name = "AuditId")
	Long id;

	@Column(name = "ChangedByCode")
	UUID changedByCode;

	@Column(name = "ChangedByName")
	String changedByName;

	@Column(name = "ChangeType")
	String changeType;

	@Column(name = "FieldChanged")
	String field;

	@Column(name = "OldValue")
	String oldValue;

	@Column(name = "NewValue")
	String newValue;

	/** The JSON column, as text. */
	@Column(name = "AdditionalData")
	String additionalData;

	@Column(name = "CreatedAt")
	Instant createdAt;

}
