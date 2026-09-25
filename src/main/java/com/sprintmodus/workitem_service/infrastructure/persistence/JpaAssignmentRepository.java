package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;

@Repository
class JpaAssignmentRepository implements AssignmentRepository {

	private final AssignmentQueries assignments;

	JpaAssignmentRepository(AssignmentQueries assignments) {
		this.assignments = assignments;
	}

	@Override
	public List<Assignment> findByWorkItem(UUID workItemCode) {
		return assignments.findByWorkItem(workItemCode.toString()).stream().map(JpaAssignmentRepository::toDomain).toList();
	}

	@Override
	public Map<UUID, List<Assignment>> findByWorkItems(Collection<UUID> workItemCodes) {
		if (workItemCodes.isEmpty()) {
			return Map.of();
		}
		String codes = workItemCodes.stream().map(code -> "\"" + code + "\"").collect(Collectors.joining(",", "[", "]"));
		return assignments.findByWorkItems(codes).stream()
				.collect(Collectors.groupingBy(entity -> entity.workItemCode, Collectors.mapping(JpaAssignmentRepository::toDomain, Collectors.toList())));
	}

	@Override
	public Optional<Assignment> find(UUID workItemCode, UUID assignmentCode) {
		return assignments.find(workItemCode.toString(), assignmentCode.toString()).map(JpaAssignmentRepository::toDomain);
	}

	@Override
	public boolean isAssigned(UUID workItemCode, UUID userCode, AssignmentRole role) {
		return assignments.findActive(workItemCode.toString(), userCode.toString(), role.name()).isPresent();
	}

	@Override
	public Optional<Assignment> assign(UUID workItemCode, UUID userCode, AssignmentRole role) {
		int changed = assignments.assign(UUID.randomUUID().toString(), workItemCode.toString(), userCode.toString(), role.name());
		if (changed == 0) {
			return Optional.empty();
		}
		return assignments.findActive(workItemCode.toString(), userCode.toString(), role.name()).map(JpaAssignmentRepository::toDomain);
	}

	@Override
	public boolean unassign(UUID workItemCode, UUID assignmentCode) {
		return assignments.unassign(workItemCode.toString(), assignmentCode.toString()) > 0;
	}

	private static Assignment toDomain(AssignmentEntity entity) {
		return new Assignment(entity.code, entity.userCode, entity.fullName, entity.role);
	}

}
