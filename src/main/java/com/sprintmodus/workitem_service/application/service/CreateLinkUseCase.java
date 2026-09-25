package com.sprintmodus.workitem_service.application.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateLink;
import com.sprintmodus.workitem_service.application.dto.Responses.LinkResponse;
import com.sprintmodus.workitem_service.application.error.LinkError;
import com.sprintmodus.workitem_service.application.error.LinkError.AlreadyLinked;
import com.sprintmodus.workitem_service.application.error.LinkError.CyclicLink;
import com.sprintmodus.workitem_service.application.error.LinkError.InvalidLink;
import com.sprintmodus.workitem_service.application.error.LinkError.TargetNotFound;
import com.sprintmodus.workitem_service.application.error.LinkError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Link;

/**
 * Links two work items (of any types, even across projects) and records {@code LINKED} with the acting user. Rejects a
 * self-link, a link that already exists with this type, and a link that would close a cycle within this type's graph
 * (e.g. a chain of BLOCKS back on itself) — checked with a recursive reachability query, kept per link type: a
 * RELATED_TO cycle is harmless, but a BLOCKS cycle means nothing could ever start.
 */
@Service
public class CreateLinkUseCase {

	private final WorkItemRepository items;

	private final LinkRepository links;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	public CreateLinkUseCase(WorkItemRepository items, LinkRepository links, AuditRepository audit, TransactionRunner transactions) {
		this.items = items;
		this.links = links;
		this.audit = audit;
		this.transactions = transactions;
	}

	public Result<LinkResponse, LinkError> execute(CreateLink command) {
		if (command.targetCode() == null) {
			return Result.failure(new InvalidLink("targetCode", "Choose the work item to link to."));
		}
		if (command.type() == null) {
			return Result.failure(new InvalidLink("type", "Choose the kind of link."));
		}
		if (command.targetCode().equals(command.workItemCode())) {
			return Result.failure(new InvalidLink("targetCode", "A work item cannot be linked to itself."));
		}
		if (items.findByCode(command.workItemCode()).isEmpty()) {
			return Result.failure(new WorkItemNotFound());
		}

		return transactions.inTransaction(() -> {
			if (links.exists(command.workItemCode(), command.targetCode(), command.type())) {
				return Result.<LinkResponse, LinkError>failure(new AlreadyLinked());
			}
			if (links.wouldCreateCycle(command.workItemCode(), command.targetCode(), command.type())) {
				return Result.<LinkResponse, LinkError>failure(new CyclicLink());
			}
			Link link = links.create(command.workItemCode(), command.targetCode(), command.type(), command.actor().userCode())
					.orElse(null);
			if (link == null) {
				// the source was checked above, so the target is what is missing (or was deleted in the meantime)
				return Result.<LinkResponse, LinkError>failure(new TargetNotFound());
			}
			audit.append(new AuditEntry(command.workItemCode(), command.actor().userCode(), ChangeType.LINKED, "Link", null,
					label(link), Map.of("targetCode", link.other().code().toString(), "type", link.type().name())));
			return Result.<LinkResponse, LinkError>success(LinkResponse.from(link));
		});
	}

	private static String label(Link link) {
		return link.other().displayKey() + " (" + link.type() + ")";
	}

}
