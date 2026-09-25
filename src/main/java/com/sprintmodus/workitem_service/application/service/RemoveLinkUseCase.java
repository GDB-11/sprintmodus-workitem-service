package com.sprintmodus.workitem_service.application.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.LinkError;
import com.sprintmodus.workitem_service.application.error.LinkError.LinkNotFound;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.Link;

/** Removes a link (deactivated, so re-creating the same link, type and direction brings it back) and records {@code UNLINKED}. */
@Service
public class RemoveLinkUseCase {

	private final LinkRepository links;

	private final AuditRepository audit;

	private final TransactionRunner transactions;

	public RemoveLinkUseCase(LinkRepository links, AuditRepository audit, TransactionRunner transactions) {
		this.links = links;
		this.audit = audit;
		this.transactions = transactions;
	}

	public Result<Unit, LinkError> execute(Actor actor, UUID workItemCode, UUID linkCode) {
		Link link = links.find(workItemCode, linkCode).orElse(null);
		if (link == null) {
			return Result.failure(new LinkNotFound());
		}
		return transactions.inTransaction(() -> {
			if (!links.unlink(workItemCode, linkCode)) {
				return Result.<Unit, LinkError>failure(new LinkNotFound());
			}
			audit.append(new AuditEntry(workItemCode, actor.userCode(), ChangeType.UNLINKED, "Link",
					link.other().displayKey() + " (" + link.type() + ")", null,
					Map.of("targetCode", link.other().code().toString(), "type", link.type().name())));
			return Result.<Unit, LinkError>success(Unit.VALUE);
		});
	}

}
