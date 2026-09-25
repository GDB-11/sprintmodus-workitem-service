package com.sprintmodus.workitem_service.infrastructure.persistence;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;

import tools.jackson.databind.json.JsonMapper;

@Repository
class JpaAuditRepository implements AuditRepository {

	private final WorkItemAuditQueries audit;

	private final JsonMapper json;

	JpaAuditRepository(WorkItemAuditQueries audit, JsonMapper json) {
		this.audit = audit;
		this.json = json;
	}

	@Override
	public void append(AuditEntry entry) {
		String additionalData = entry.additionalData().isEmpty() ? "" : json.writeValueAsString(entry.additionalData());
		int inserted = audit.insert(entry.workItemCode().toString(), entry.changedBy().toString(), entry.type().name(),
				orEmpty(entry.field()), orEmpty(entry.oldValue()), orEmpty(entry.newValue()), additionalData);
		if (inserted == 0) {
			// Failing here rolls back the change this entry describes: a change is never left without its history
			throw new IllegalStateException("The audit entry could not be written: unknown work item or user");
		}
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

}
