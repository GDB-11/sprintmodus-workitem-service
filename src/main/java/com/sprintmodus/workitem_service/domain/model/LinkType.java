package com.sprintmodus.workitem_service.domain.model;

/** The kind of relationship a link records between two work items. Each value is its own directed edge label: the
 * caller picks {@code BLOCKS} or {@code IS_BLOCKED_BY} explicitly depending on which item they are linking from. */
public enum LinkType {

	RELATED_TO,
	BLOCKS,
	IS_BLOCKED_BY,
	DUPLICATES,
	IS_DUPLICATED_BY

}
