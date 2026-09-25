package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.LinkType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence model of a row of {@code WorkItemLink}, with the code of whichever end is not the item the query was
 * made for. */
@Entity
@Table(name = "WorkItemLink")
class LinkEntity {

	@Id
	@Column(name = "LinkId")
	Long id;

	@Column(name = "LinkCode")
	UUID code;

	@Enumerated(EnumType.STRING)
	@Column(name = "LinkType")
	LinkType type;

	@Column(name = "OtherCode")
	UUID otherCode;

}
