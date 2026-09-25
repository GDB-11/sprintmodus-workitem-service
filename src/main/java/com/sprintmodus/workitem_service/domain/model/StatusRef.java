package com.sprintmodus.workitem_service.domain.model;

/**
 * The workflow status a work item is in. {@code initial} is the status new items start in (the lowest order); {@code terminal}
 * statuses count as completed. How a status looks (colors, icons) is up to each client, never stored here.
 */
public record StatusRef(String code, String displayName, boolean initial, boolean terminal) {
}
