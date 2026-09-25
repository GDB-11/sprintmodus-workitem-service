package com.sprintmodus.workitem_service.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How many active work items are in a status, as counted by {@link StatusUsageQueries}. */
@Entity
@Table(name = "WorkItemStatus")
class StatusUsageEntity {

	@Id
	@Column(name = "StatusCode")
	String statusCode;

	@Column(name = "Items")
	long items;

}
