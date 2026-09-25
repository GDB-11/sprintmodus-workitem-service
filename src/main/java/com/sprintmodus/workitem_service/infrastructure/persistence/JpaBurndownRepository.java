package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sprintmodus.workitem_service.application.port.persistence.BurndownRepository;

/**
 * The snapshot is one statement that runs after the change it describes has committed, so it has a transaction of its own
 * (a native {@code INSERT} needs one). The tenant is already in the tenant context, set by the request's security filter.
 */
@Repository
class JpaBurndownRepository implements BurndownRepository {

	private final BurndownQueries burndown;

	private final TransactionTemplate transaction;

	JpaBurndownRepository(BurndownQueries burndown, PlatformTransactionManager transactionManager) {
		this.burndown = burndown;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Override
	public void snapshot(UUID sprintCode) {
		transaction.executeWithoutResult(_ -> burndown.snapshot(sprintCode.toString()));
	}

}
