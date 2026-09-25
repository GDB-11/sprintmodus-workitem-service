package com.sprintmodus.workitem_service.infrastructure.persistence;

import com.sprintmodus.workitem_service.domain.model.ItemType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a row of {@code WorkItemStatus}: one status of one item type's workflow. */
@Entity
@Table(name = "WorkItemStatus")
class WorkflowStatusEntity {

	@Id
	@Column(name = "StatusId")
	Long id;

	@Column(name = "StatusCode")
	String code;

	@Column(name = "DisplayName")
	String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "ItemType")
	ItemType itemType;

	@Column(name = "SortOrder")
	int order;

	@Column(name = "IsTerminal")
	boolean terminal;

	@Column(name = "IsActive")
	boolean active;

}
