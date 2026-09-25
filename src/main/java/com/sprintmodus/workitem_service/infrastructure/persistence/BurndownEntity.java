package com.sprintmodus.workitem_service.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of {@code BurndownData}; only ever written, by {@link BurndownQueries}. */
@Entity
@Table(name = "BurndownData")
class BurndownEntity {

	@Id
	@Column(name = "BurndownId")
	Long id;

}
