package com.sprintmodus.workitem_service.domain.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/** Turns an edit of a work item into the audit entries that describe it: one per field that really changed. */
public final class WorkItemDiff {

	/** Long text is cut in the history; the item itself keeps the whole value. */
	static final int MAX_AUDIT_VALUE = 500;

	private WorkItemDiff() {
	}

	/** The values an edit leaves the item with. */
	public record After(String title, String description, String acceptanceCriteria, Priority priority, int effortPoints,
			BigDecimal estimatedHours, BigDecimal remainingHours) {
	}

	public static List<AuditEntry> between(WorkItem before, After after, UUID changedBy) {
		List<AuditEntry> entries = new ArrayList<>();
		UUID code = before.code();
		if (!before.title().equals(after.title())) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.FIELD_CHANGED, "Title", before.title(), after.title()));
		}
		if (!Objects.equals(before.description(), after.description())) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.DESCRIPTION_EDITED, "Description", cut(before.description()),
					cut(after.description())));
		}
		if (!Objects.equals(before.acceptanceCriteria(), after.acceptanceCriteria())) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.FIELD_CHANGED, "AcceptanceCriteria",
					cut(before.acceptanceCriteria()), cut(after.acceptanceCriteria())));
		}
		if (before.priority() != after.priority()) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.FIELD_CHANGED, "Priority", before.priority().name(),
					after.priority().name()));
		}
		if (before.effortPoints() != after.effortPoints()) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.EFFORT_CHANGED, "EffortPoints",
					String.valueOf(before.effortPoints()), String.valueOf(after.effortPoints())));
		}
		hours(entries, code, changedBy, "EstimatedHours", before.estimatedHours(), after.estimatedHours());
		hours(entries, code, changedBy, "RemainingHours", before.remainingHours(), after.remainingHours());
		return entries;
	}

	private static void hours(List<AuditEntry> entries, UUID code, UUID changedBy, String field, BigDecimal before, BigDecimal after) {
		// 8 and 8.00 are the same number
		if (before.compareTo(after) != 0) {
			entries.add(new AuditEntry(code, changedBy, ChangeType.EFFORT_CHANGED, field, before.stripTrailingZeros().toPlainString(),
					after.stripTrailingZeros().toPlainString()));
		}
	}

	static String cut(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= MAX_AUDIT_VALUE ? value : value.substring(0, MAX_AUDIT_VALUE);
	}

}
