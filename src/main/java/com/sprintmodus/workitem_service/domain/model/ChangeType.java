package com.sprintmodus.workitem_service.domain.model;

/** What kind of change an audit entry records. Mirrors the {@code WorkItemAudit.ChangeType} column. */
public enum ChangeType {

	CREATED,
	STATE_CHANGED,
	ASSIGNED,
	UNASSIGNED,
	EFFORT_CHANGED,
	DESCRIPTION_EDITED,
	FIELD_CHANGED,
	PARENT_CHANGED,
	SPRINT_CHANGED,
	COMMENTED,
	COMMENT_EDITED,
	COMMENT_DELETED,
	LINKED,
	UNLINKED,
	DELETED,
	RESTORED

}
