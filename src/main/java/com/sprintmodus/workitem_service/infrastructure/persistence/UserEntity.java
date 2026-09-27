package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only persistence model of a tenant user: only what this service shows (never the e-mail or the password hash). */
@Entity
@Table(name = "User")
class UserEntity {

	@Id
	@Column(name = "UserId")
	Long id;

	@Column(name = "UserCode")
	UUID code;

	@Column(name = "FullName")
	String fullName;

}
