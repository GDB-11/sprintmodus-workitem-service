package com.sprintmodus.workitem_service.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidParent;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Warning;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.service.WorkItemHierarchy;

/**
 * What decides whether a work item may have a given parent. The hard rules reject: the parent must exist, be in the same
 * project, and not be the item itself or one of its descendants. The recommended type hierarchy only warns.
 */
final class ParentRules {

	private ParentRules() {
	}

	/**
	 * @param childCode the item that is getting the parent, or {@code null} while it is being created
	 * @return the warnings to show if the parent is allowed, or the reason it is not
	 */
	static Result<List<Warning>, WorkItemError> check(WorkItemRepository items, ItemType childType, UUID childCode, UUID projectCode,
			UUID parentCode) {
		WorkItem parent = items.findByCode(parentCode).orElse(null);
		if (parent == null) {
			return Result.failure(new InvalidParent("The parent work item was not found."));
		}
		if (!parent.projectCode().equals(projectCode)) {
			return Result.failure(new InvalidParent("The parent must be in the same project."));
		}
		if (childCode != null && items.isSelfOrDescendant(childCode, parentCode)) {
			return Result.failure(new InvalidParent("A work item cannot be its own ancestor."));
		}
		List<Warning> warnings = new ArrayList<>();
		// Not a rule, only advice: the operation goes ahead either way
		WorkItemHierarchy.validateHierarchy(childType, parent.type())
				.tapError(message -> warnings.add(new Warning(Warning.NON_STANDARD_HIERARCHY, message)));
		return Result.success(warnings);
	}

}
