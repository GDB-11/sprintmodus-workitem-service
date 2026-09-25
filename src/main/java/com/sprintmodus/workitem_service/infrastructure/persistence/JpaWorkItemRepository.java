package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.StatusRef;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * The {@link WorkItemRepository} port over the native queries of {@link WorkItemQueries}. The tenant's database is chosen
 * by the tenant context; the changes here run in the transaction the use case opened, together with its audit entry.
 */
@Repository
class JpaWorkItemRepository implements WorkItemRepository {

	/** The value bound for a UUID condition that is switched off. */
	private static final String NO_UUID = "00000000-0000-0000-0000-000000000000";

	private final WorkItemQueries items;

	private final WorkItemSequenceQueries sequences;

	JpaWorkItemRepository(WorkItemQueries items, WorkItemSequenceQueries sequences) {
		this.items = items;
		this.sequences = sequences;
	}

	@Override
	public Optional<WorkItem> insert(NewWorkItem item) {
		String projectCode = item.projectCode().toString();
		Long number = sequences.lockNext(projectCode).orElse(null);
		if (number == null) {
			// no counter yet: create it (only for an active project), then take the lock
			if (sequences.createFor(projectCode) == 0) {
				return Optional.empty();
			}
			number = sequences.lockNext(projectCode).orElseThrow();
		}
		sequences.increment(projectCode);

		String code = UUID.randomUUID().toString();
		int inserted = items.insert(code, number, projectCode, item.type().name(), item.initialStatusCode(), item.title(),
				orEmpty(item.description()), orEmpty(item.acceptanceCriteria()), item.priority().name(),
				item.parentCode() == null ? NO_UUID : item.parentCode().toString(), item.effortPoints(), item.estimatedHours(),
				item.remainingHours(), item.createdBy().toString());
		if (inserted == 0) {
			throw new IllegalStateException("The work item could not be inserted: its initial status or its creator is not active");
		}
		return items.findByCode(code).map(JpaWorkItemRepository::toDomain);
	}

	@Override
	public Optional<WorkItem> findByCode(UUID code) {
		return items.findByCode(code.toString()).map(JpaWorkItemRepository::toDomain);
	}

	@Override
	public List<WorkItem> findChildren(UUID code) {
		return items.findChildren(code.toString()).stream().map(JpaWorkItemRepository::toDomain).toList();
	}

	@Override
	public Page search(Filter filter, int page, int size) {
		int hasProject = filter.projectCode() != null ? 1 : 0;
		int sprintMode = switch (filter.sprintScope()) {
			case ANY -> 0;
			case BACKLOG -> 1;
			case SPRINT -> 2;
		};
		int hasType = filter.type() != null ? 1 : 0;
		int hasStatus = filter.statusCode() != null ? 1 : 0;
		int hasParent = filter.parentCode() != null ? 1 : 0;
		int hasPriority = filter.priority() != null ? 1 : 0;
		int hasAssignee = filter.assigneeCode() != null ? 1 : 0;
		String project = uuid(filter.projectCode());
		String sprint = uuid(filter.sprintCode());
		String type = filter.type() != null ? filter.type().name() : "EPIC";
		String status = filter.statusCode() != null ? filter.statusCode() : "";
		String parent = uuid(filter.parentCode());
		String priority = filter.priority() != null ? filter.priority().name() : "LOW";
		String assignee = uuid(filter.assigneeCode());
		int hasText = filter.text() != null && !filter.text().isBlank() ? 1 : 0;
		String text = hasText == 1 ? filter.text() : "";

		long total = items.countSearch(hasProject, project, sprintMode, sprint, hasType, type, hasStatus, status, hasParent, parent,
				hasPriority, priority, hasAssignee, assignee, hasText, text);
		List<WorkItem> found = items.search(hasProject, project, sprintMode, sprint, hasType, type, hasStatus, status, hasParent, parent,
				hasPriority, priority, hasAssignee, assignee, hasText, text, filter.boardOrder() ? 1 : 0, size, page * size).stream()
				.map(JpaWorkItemRepository::toDomain).toList();
		return new Page(found, total);
	}

	@Override
	public boolean updateFields(UUID code, FieldChanges changes, UUID updatedBy) {
		return items.updateFields(code.toString(), changes.title(), orEmpty(changes.description()), orEmpty(changes.acceptanceCriteria()),
				changes.priority().name(), changes.effortPoints(), changes.estimatedHours(), changes.remainingHours(),
				updatedBy.toString()) > 0;
	}

	@Override
	public boolean setParent(UUID code, UUID parentCode, UUID updatedBy) {
		return parentCode == null ? items.clearParent(code.toString(), updatedBy.toString()) > 0
				: items.setParent(code.toString(), parentCode.toString(), updatedBy.toString()) > 0;
	}

	@Override
	public boolean changeStatus(UUID code, String fromStatusCode, String toStatusCode, UUID updatedBy) {
		return items.changeStatus(code.toString(), fromStatusCode, toStatusCode, updatedBy.toString()) > 0;
	}

	@Override
	public boolean setSprint(UUID code, UUID sprintCode, UUID updatedBy) {
		return sprintCode == null ? items.clearSprint(code.toString(), updatedBy.toString()) > 0
				: items.setSprint(code.toString(), sprintCode.toString(), updatedBy.toString()) > 0;
	}

	@Override
	public List<UUID> lockRankGroup(UUID projectCode, ItemType type, String statusCode, Priority priority) {
		return items.lockRankGroup(projectCode.toString(), type.name(), statusCode, priority.name()).stream().map(UUID::fromString).toList();
	}

	@Override
	public boolean setBoardRank(UUID code, int rank) {
		return items.setBoardRank(code.toString(), rank) > 0;
	}

	@Override
	public boolean softDelete(UUID code, UUID deletedBy) {
		return items.softDelete(code.toString(), deletedBy.toString()) > 0;
	}

	@Override
	public boolean isSelfOrDescendant(UUID ancestorCode, UUID candidateCode) {
		return items.countInSubtree(ancestorCode.toString(), candidateCode.toString()) > 0;
	}

	@Override
	public int countActiveChildren(UUID code) {
		return (int) items.countActiveChildren(code.toString());
	}

	@Override
	public int completedEffortOfSprint(UUID sprintCode) {
		return (int) items.completedEffortOfSprint(sprintCode.toString());
	}

	private static String uuid(UUID value) {
		return value == null ? NO_UUID : value.toString();
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	/** Package-visible so {@link JpaLinkRepository} can hydrate the "other" side of a link without duplicating this mapping. */
	static WorkItem toDomain(WorkItemEntity entity) {
		return new WorkItem(entity.code, entity.number, entity.projectCode, entity.projectKey, entity.type, entity.title, entity.description,
				entity.acceptanceCriteria, entity.priority, new StatusRef(entity.statusCode, entity.statusName, entity.statusInitial, entity.statusTerminal),
				entity.sprintCode, entity.parentCode, entity.effortPoints, entity.estimatedHours, entity.remainingHours,
				new UserRef(entity.createdByCode, entity.createdByName),
				entity.updatedByCode == null ? null : new UserRef(entity.updatedByCode, entity.updatedByName), entity.createdAt, entity.updatedAt,
				entity.boardRank, entity.childCount);
	}

}
