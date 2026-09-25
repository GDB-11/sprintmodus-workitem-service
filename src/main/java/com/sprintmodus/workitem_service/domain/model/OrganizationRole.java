package com.sprintmodus.workitem_service.domain.model;

/** Organization-level role of a user (from the JWT). */
public enum OrganizationRole {

	OWNER,
	ADMIN,
	MEMBER;

	/** Owners and admins may do administrative things: delete anyone's items, change the workflows. */
	public boolean canAdminister() {
		return this != MEMBER;
	}

	/** An unknown role gets the least privilege. */
	public static OrganizationRole parse(String value) {
		for (OrganizationRole role : values()) {
			if (role.name().equals(value)) {
				return role;
			}
		}
		return MEMBER;
	}

}
