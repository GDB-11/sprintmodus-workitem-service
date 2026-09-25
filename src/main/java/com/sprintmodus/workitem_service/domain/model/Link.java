package com.sprintmodus.workitem_service.domain.model;

import java.util.UUID;

/** A relationship between two work items, as the item it was loaded for sees it: {@code other} is always the item at
 * the far end, regardless of whether this item was the link's source or target. */
public record Link(UUID code, LinkType type, WorkItem other) {
}
