package com.sprintmodus.workitem_service.application.port.persistence;

import com.sprintmodus.workitem_service.domain.model.AuditEntry;

/** The append-only history of work items. Entries are written inside the transaction of the change they describe. */
public interface AuditRepository {

	void append(AuditEntry entry);

}
