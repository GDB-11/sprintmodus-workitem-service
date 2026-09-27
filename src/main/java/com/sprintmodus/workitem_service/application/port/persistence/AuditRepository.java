package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.HistoryEntry;

/** The append-only history of work items. Entries are written inside the transaction of the change they describe. */
public interface AuditRepository {

	/** One page of a work item's history. {@code itemActive} is false once the item is soft-deleted. */
	record History(boolean itemActive, List<HistoryEntry> entries, long total) {
	}

	void append(AuditEntry entry);

	/**
	 * The history of a work item, newest first (ties broken by the order they were written), whether or not the item is
	 * still active: its deletion is part of it. Empty if no such item exists.
	 */
	Optional<History> history(UUID workItemCode, int page, int size);

}
