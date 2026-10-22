package com.sprintmodus.workitem_service.domain.model;

/**
 * What a notification is about. Stored as a machine code (an extensible {@code VARCHAR}, not a database enum), so a later
 * phase adds a type here without a migration. It is never display text: the client writes the sentence.
 */
public enum NotificationType {

	MENTIONED,

	ASSIGNED

}
