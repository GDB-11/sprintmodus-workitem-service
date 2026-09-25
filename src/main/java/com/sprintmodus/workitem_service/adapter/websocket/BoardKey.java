package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.UUID;

/** One project board of one tenant: the unit events are broadcast to. */
public record BoardKey(UUID tenantId, UUID projectCode) {

	/** The topic every client of this board subscribes to. Includes the tenant, so a topic never spans tenants. */
	public String topic() {
		return "/topic/tenant/" + tenantId + "/project/" + projectCode;
	}

	/** Where clients publish for this board; everything they send must start with it. */
	public String applicationPrefix() {
		return "/app/tenant/" + tenantId + "/project/" + projectCode + "/";
	}

}
