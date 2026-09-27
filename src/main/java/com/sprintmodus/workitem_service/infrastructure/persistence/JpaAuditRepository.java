package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.HistoryEntry;
import com.sprintmodus.workitem_service.domain.model.UserRef;

import tools.jackson.core.type.TypeReference;
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

	@Override
	public Optional<History> history(UUID workItemCode, int page, int size) {
		String code = workItemCode.toString();
		return audit.isActive(code).map(active -> {
			List<HistoryEntry> entries = audit.findByWorkItem(code, size, (long) page * size).stream().map(this::toDomain).toList();
			return new History(active, entries, audit.countByWorkItem(code));
		});
	}

	private HistoryEntry toDomain(WorkItemAuditEntity entity) {
		Map<String, String> data = entity.additionalData == null ? Map.of()
				: json.readValue(entity.additionalData, new TypeReference<Map<String, String>>() {
				});
		return new HistoryEntry(ChangeType.valueOf(entity.changeType), entity.field, entity.oldValue, entity.newValue, data,
				new UserRef(entity.changedByCode, entity.changedByName), entity.createdAt);
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

}
