package com.sprintmodus.workitem_service.domain.model;

/** The kinds of work item. The hierarchy between them is recommended, not enforced (see {@code WorkItemHierarchy}). */
public enum ItemType {

	EPIC,
	FEATURE,
	PBI,
	BUG,
	TASK

}
