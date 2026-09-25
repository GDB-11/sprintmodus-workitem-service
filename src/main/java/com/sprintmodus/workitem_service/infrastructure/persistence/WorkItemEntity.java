package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistence model of a work item joined with everything a caller shows about it (project key, status, sprint and parent
 * codes, people), as returned by the native queries in {@link WorkItemQueries}. It is never used to generate SQL.
 */
@Entity
@Table(name = "WorkItem")
class WorkItemEntity {

	@Id
	@Column(name = "WorkItemId")
	Long id;

	@Column(name = "WorkItemCode")
	UUID code;

	@Column(name = "WorkItemNumber")
	long number;

	@Column(name = "ProjectCode")
	UUID projectCode;

	@Column(name = "ProjectKey")
	String projectKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "Type")
	ItemType type;

	@Column(name = "Title")
	String title;

	@Column(name = "Description")
	String description;

	@Column(name = "AcceptanceCriteria")
	String acceptanceCriteria;

	@Enumerated(EnumType.STRING)
	@Column(name = "Priority")
	Priority priority;

	@Column(name = "BoardRank")
	Integer boardRank;

	@Column(name = "ChildCount")
	int childCount;

	@Column(name = "StatusCode")
	String statusCode;

	@Column(name = "StatusName")
	String statusName;

	@Column(name = "StatusInitial")
	boolean statusInitial;

	@Column(name = "StatusTerminal")
	boolean statusTerminal;

	@Column(name = "SprintCode")
	UUID sprintCode;

	@Column(name = "ParentCode")
	UUID parentCode;

	@Column(name = "EffortPoints")
	int effortPoints;

	@Column(name = "EstimatedHours")
	BigDecimal estimatedHours;

	@Column(name = "RemainingHours")
	BigDecimal remainingHours;

	@Column(name = "CreatedByCode")
	UUID createdByCode;

	@Column(name = "CreatedByName")
	String createdByName;

	@Column(name = "UpdatedByCode")
	UUID updatedByCode;

	@Column(name = "UpdatedByName")
	String updatedByName;

	@Column(name = "CreatedAt")
	Instant createdAt;

	@Column(name = "UpdatedAt")
	Instant updatedAt;

}
