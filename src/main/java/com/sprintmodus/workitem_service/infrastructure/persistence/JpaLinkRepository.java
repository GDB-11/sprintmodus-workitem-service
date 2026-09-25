package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/** The {@link LinkRepository} port over the native queries of {@link LinkQueries}. The "other" side of each link is
 * hydrated through {@link WorkItemQueries}, reusing {@link JpaWorkItemRepository}'s entity-to-domain mapping. */
@Repository
class JpaLinkRepository implements LinkRepository {

	private final LinkQueries links;

	private final WorkItemQueries items;

	JpaLinkRepository(LinkQueries links, WorkItemQueries items) {
		this.links = links;
		this.items = items;
	}

	@Override
	public List<Link> findByWorkItem(UUID workItemCode) {
		return links.findByWorkItem(workItemCode.toString()).stream().map(this::toDomain).toList();
	}

	@Override
	public Optional<Link> find(UUID workItemCode, UUID linkCode) {
		return links.find(workItemCode.toString(), linkCode.toString()).map(this::toDomain);
	}

	@Override
	public boolean exists(UUID sourceCode, UUID targetCode, LinkType type) {
		return links.findActive(sourceCode.toString(), targetCode.toString(), type.name()).isPresent();
	}

	@Override
	public boolean wouldCreateCycle(UUID sourceCode, UUID targetCode, LinkType type) {
		return links.countCyclePath(sourceCode.toString(), targetCode.toString(), type.name()) > 0;
	}

	@Override
	public Optional<Link> create(UUID sourceCode, UUID targetCode, LinkType type, UUID createdBy) {
		int changed = links.create(UUID.randomUUID().toString(), sourceCode.toString(), targetCode.toString(), type.name(),
				createdBy.toString());
		if (changed == 0) {
			return Optional.empty();
		}
		return links.findActive(sourceCode.toString(), targetCode.toString(), type.name()).map(this::toDomain);
	}

	@Override
	public boolean unlink(UUID workItemCode, UUID linkCode) {
		return links.unlink(workItemCode.toString(), linkCode.toString()) > 0;
	}

	private Link toDomain(LinkEntity entity) {
		WorkItem other = items.findByCode(entity.otherCode.toString()).map(JpaWorkItemRepository::toDomain).orElseThrow();
		return new Link(entity.code, entity.type, other);
	}

}
