package com.sprintmodus.workitem_service.application.port.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Work items of the current tenant (the database is chosen by the tenant context, which the request's security filter
 * sets). Methods that change data expect to run inside a {@code TransactionRunner} transaction together with the audit
 * entry that describes the change.
 */
public interface WorkItemRepository {

	record NewWorkItem(UUID projectCode, ItemType type, String initialStatusCode, String title, String description,
			String acceptanceCriteria, Priority priority, UUID parentCode, int effortPoints, BigDecimal estimatedHours,
			BigDecimal remainingHours, UUID createdBy) {
	}

	/** The values an update leaves an item with. */
	record FieldChanges(String title, String description, String acceptanceCriteria, Priority priority, int effortPoints,
			BigDecimal estimatedHours, BigDecimal remainingHours) {
	}

	/** {@code text}, when given, is matched against the title and description with the {@code ft_work_item_search}
	 * full-text index (natural language mode). {@code boardOrder} lists in the order of a board column (priority, then manual
	 * rank, then number) instead of newest number first. */
	record Filter(UUID projectCode, SprintScope sprintScope, UUID sprintCode, ItemType type, String statusCode, UUID parentCode,
			UUID assigneeCode, Priority priority, String text, boolean boardOrder) {

		public enum SprintScope {

			ANY,
			BACKLOG,
			SPRINT

		}

	}

	record Page(List<WorkItem> items, long total) {
	}

	/**
	 * Numbers the item with its project's next number (the first is 1000) and inserts it in its initial status. Empty if
	 * the project does not exist or is deleted.
	 */
	Optional<WorkItem> insert(NewWorkItem item);

	Optional<WorkItem> findByCode(UUID code);

	List<WorkItem> findChildren(UUID code);

	/** Active items matching the filter, newest number first (or in board order, see {@link Filter}). */
	Page search(Filter filter, int page, int size);

	/** Returns whether an active item was updated. */
	boolean updateFields(UUID code, FieldChanges changes, UUID updatedBy);

	/** {@code parentCode == null} removes the parent. Returns whether the change was applied. */
	boolean setParent(UUID code, UUID parentCode, UUID updatedBy);

	/**
	 * Moves the item to {@code toStatusCode}, only if it is still in {@code fromStatusCode}: the guard that stops two
	 * concurrent changes from both succeeding. Returns whether the change was applied.
	 */
	boolean changeStatus(UUID code, String fromStatusCode, String toStatusCode, UUID updatedBy);

	/** {@code sprintCode == null} moves the item to the backlog. Returns whether the change was applied. */
	boolean setSprint(UUID code, UUID sprintCode, UUID updatedBy);

	boolean softDelete(UUID code, UUID deletedBy);

	/**
	 * The codes of the active items of a project that share a rank group (same type, status and priority), in board order.
	 * Locks them until the transaction ends, so it must be called inside one.
	 */
	List<UUID> lockRankGroup(UUID projectCode, ItemType type, String statusCode, Priority priority);

	/** Sets an item's manual rank without recording it as an edit of the item. Returns whether the rank changed. */
	boolean setBoardRank(UUID code, int rank);

	/** Whether {@code candidateCode} is {@code ancestorCode} itself or one of its descendants. */
	boolean isSelfOrDescendant(UUID ancestorCode, UUID candidateCode);

	int countActiveChildren(UUID code);

	/** Effort points of the PBIs and Bugs of a sprint that are in a terminal status. */
	int completedEffortOfSprint(UUID sprintCode);

}
