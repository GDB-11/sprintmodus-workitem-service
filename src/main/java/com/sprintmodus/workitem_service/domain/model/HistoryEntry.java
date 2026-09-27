package com.sprintmodus.workitem_service.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * One line of a work item's history as it is read back: an {@link AuditEntry} with who made the change and when.
 *
 * @param field the field that changed (e.g. {@code Status}), or {@code null}
 * @param oldValue previous value, or {@code null}
 * @param newValue new value, or {@code null}
 * @param additionalData extra detail written with the change (e.g. the display names of a status change), may be empty
 * @param changedBy who made the change
 */
public record HistoryEntry(ChangeType type, String field, String oldValue, String newValue, Map<String, String> additionalData,
		UserRef changedBy, Instant createdAt) {
}
