package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.LinkType;

public interface LinkRepository {

	/** Active links where the item is the source or the target, oldest first. */
	List<Link> findByWorkItem(UUID workItemCode);

	/** A link by its code, only if {@code workItemCode} is one of its two ends. */
	Optional<Link> find(UUID workItemCode, UUID linkCode);

	boolean exists(UUID sourceCode, UUID targetCode, LinkType type);

	/** Whether {@code source -> target} of this type would close a cycle: whether {@code source} is already reachable
	 * from {@code target} by following this type's active links forward. */
	boolean wouldCreateCycle(UUID sourceCode, UUID targetCode, LinkType type);

	/**
	 * Links an active source to an active target. If the same source, target and type were linked before and then
	 * removed, that row comes back (the unique key covers inactive rows too). Empty if either item does not exist or
	 * is deleted.
	 */
	Optional<Link> create(UUID sourceCode, UUID targetCode, LinkType type, UUID createdBy);

	/** Returns whether an active link was removed. */
	boolean unlink(UUID workItemCode, UUID linkCode);

}
