package com.sprintmodus.workitem_service.application.port.external;

import java.util.function.Supplier;

/**
 * Runs work in one database transaction on the current tenant's database, so a change and its audit entry commit or roll
 * back together. If the work throws, the transaction is rolled back and the exception propagates. Calls to other
 * services must stay outside it.
 */
public interface TransactionRunner {

	<T> T inTransaction(Supplier<T> work);

}
