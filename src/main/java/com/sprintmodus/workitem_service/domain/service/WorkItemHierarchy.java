package com.sprintmodus.workitem_service.domain.service;

import java.util.List;
import java.util.Map;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.domain.model.ItemType;

/**
 * The recommended hierarchy: an Epic has no parent, a Feature sits under an Epic, a PBI or Bug under an Epic or a
 * Feature, a Task under a PBI or a Bug. It is <em>recommended, not enforced</em>: a non-standard parent still succeeds,
 * and the failure this returns is only the warning text to show.
 */
public final class WorkItemHierarchy {

	private static final Map<ItemType, List<ItemType>> RECOMMENDED_PARENTS = Map.of(
			ItemType.EPIC, List.of(),
			ItemType.FEATURE, List.of(ItemType.EPIC),
			ItemType.PBI, List.of(ItemType.EPIC, ItemType.FEATURE),
			ItemType.BUG, List.of(ItemType.EPIC, ItemType.FEATURE),
			ItemType.TASK, List.of(ItemType.PBI, ItemType.BUG));

	private WorkItemHierarchy() {
	}

	/** Success if {@code parent} is a recommended parent of {@code child}; otherwise a failure carrying the warning message. */
	public static Result<Unit, String> validateHierarchy(ItemType child, ItemType parent) {
		if (RECOMMENDED_PARENTS.getOrDefault(child, List.of()).contains(parent)) {
			return Result.success(Unit.VALUE);
		}
		return Result.failure(parent + " is not a recommended parent for " + child + ". Consider reorganizing the items.");
	}

}
