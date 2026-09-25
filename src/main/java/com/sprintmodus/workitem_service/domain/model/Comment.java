package com.sprintmodus.workitem_service.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Comment(UUID code, UUID workItemCode, UserRef author, String content, Instant createdAt) {
}
