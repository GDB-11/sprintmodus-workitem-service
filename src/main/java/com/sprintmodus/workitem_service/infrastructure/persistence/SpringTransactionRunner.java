package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;

/**
 * Runs a use case's database work in one transaction on the current tenant's database. The tenant must already be in the
 * tenant context, which the request's security filter sets: that is when the transaction's connection is chosen.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

	private final TransactionTemplate template;

	SpringTransactionRunner(PlatformTransactionManager transactionManager) {
		this.template = new TransactionTemplate(transactionManager);
	}

	@Override
	public <T> T inTransaction(Supplier<T> work) {
		return template.execute(_ -> work.get());
	}

}
