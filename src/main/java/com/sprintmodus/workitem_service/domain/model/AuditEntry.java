package com.sprintmodus.workitem_service.domain.model;

import java.util.Map;
import java.util.UUID;

/**
 * One line of a work item's history, written by the use case that made the change, in the same transaction.
 *
 * @param field the field that changed (e.g. {@code Status}), or {@code null}
 * @param oldValue previous value, or {@code null}
 * @param newValue new value, or {@code null}
 * @param additionalData extra detail (e.g. the other end of a link), may be empty
 */
public record AuditEntry(UUID workItemCode, UUID changedBy, ChangeType type, String field, String oldValue, String newValue,
		Map<String, String> additionalData) {

	public AuditEntry(UUID workItemCode, UUID changedBy, ChangeType type, String field, String oldValue, String newValue) {
		this(workItemCode, changedBy, type, field, oldValue, newValue, Map.of());
	}

}
