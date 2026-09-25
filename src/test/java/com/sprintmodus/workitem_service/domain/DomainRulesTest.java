package com.sprintmodus.workitem_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.StatusRef;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowTransition;
import com.sprintmodus.workitem_service.domain.service.EffortPointsService;
import com.sprintmodus.workitem_service.domain.service.WorkItemDiff;
import com.sprintmodus.workitem_service.domain.service.WorkItemHierarchy;
import com.sprintmodus.workitem_service.support.Fakes;

class DomainRulesTest {

	// ------------------------------------------------------------------ hierarchy: recommended, never enforced

	@ParameterizedTest
	@CsvSource({ "FEATURE,EPIC", "PBI,EPIC", "PBI,FEATURE", "BUG,EPIC", "BUG,FEATURE", "TASK,PBI", "TASK,BUG" })
	void theRecommendedParentsPass(ItemType child, ItemType parent) {
		assertThat(WorkItemHierarchy.validateHierarchy(child, parent).isSuccess()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "EPIC,EPIC", "EPIC,FEATURE", "EPIC,PBI", "FEATURE,PBI", "FEATURE,FEATURE", "TASK,TASK", "TASK,EPIC", "PBI,TASK", "PBI,PBI", "BUG,PBI" })
	void anyOtherParentGivesAWarningMessageNotAnError(ItemType child, ItemType parent) {
		var result = WorkItemHierarchy.validateHierarchy(child, parent);

		assertThat(result.isFailure()).isTrue();
		assertThat(result.getError()).contains(parent.name()).contains("not a recommended parent").contains(child.name());
	}

	// ------------------------------------------------------------------ effort

	@Test
	void effortMustBeWithinLimits() {
		BigDecimal zero = BigDecimal.ZERO;
		assertThat(EffortPointsService.validateEffort(0, zero, zero).isSuccess()).isTrue();
		assertThat(EffortPointsService.validateEffort(1000, new BigDecimal("10000"), new BigDecimal("0.25")).isSuccess()).isTrue();
		assertThat(EffortPointsService.validateEffort(-1, zero, zero).getError().field()).isEqualTo("effortPoints");
		assertThat(EffortPointsService.validateEffort(1001, zero, zero).getError().field()).isEqualTo("effortPoints");
		assertThat(EffortPointsService.validateEffort(1, new BigDecimal("-0.5"), zero).getError().field()).isEqualTo("estimatedHours");
		assertThat(EffortPointsService.validateEffort(1, new BigDecimal("10000.01"), zero).getError().field()).isEqualTo("estimatedHours");
		assertThat(EffortPointsService.validateEffort(1, zero, new BigDecimal("-1")).getError().field()).isEqualTo("remainingHours");
	}

	@Test
	void hoursKeepAtMostTwoDecimalsBecauseTheColumnWouldRoundThemSilently() {
		BigDecimal zero = BigDecimal.ZERO;

		assertThat(EffortPointsService.validateEffort(1, new BigDecimal("1.25"), zero).isSuccess()).isTrue();
		assertThat(EffortPointsService.validateEffort(1, new BigDecimal("1.250"), zero).isSuccess()).as("trailing zero is fine").isTrue();
		assertThat(EffortPointsService.validateEffort(1, new BigDecimal("1.255"), zero).getError().field()).isEqualTo("estimatedHours");
	}

	// ------------------------------------------------------------------ workflow rules

	private static Workflow workflow() {
		return new Workflow(ItemType.PBI,
				java.util.List.of(new WorkflowStatus("NEW", "New", 1, false, true), new WorkflowStatus("APPROVED", "Approved", 2, false, true),
						new WorkflowStatus("DONE", "Done", 3, true, true), new WorkflowStatus("OLD", "Retired", 4, false, false)),
				java.util.List.of(new WorkflowTransition("NEW", "APPROVED", true), new WorkflowTransition("APPROVED", "DONE", false),
						new WorkflowTransition("DONE", "OLD", true)));
	}

	@Test
	void anItemStartsInTheActiveStatusWithTheLowestOrder() {
		assertThat(workflow().initialStatus().orElseThrow().code()).isEqualTo("NEW");
		var shuffled = new Workflow(ItemType.TASK, java.util.List.of(new WorkflowStatus("B", "B", 5, false, true), new WorkflowStatus("A", "A", 2, false, true),
				new WorkflowStatus("X", "X", 1, false, false)), java.util.List.of());
		assertThat(shuffled.initialStatus().orElseThrow().code()).as("a retired status never starts anything").isEqualTo("A");
		assertThat(new Workflow(ItemType.TASK, java.util.List.of(), java.util.List.of()).initialStatus()).isEmpty();
	}

	@Test
	void aStatusKnowsWhetherItIsTheInitialOrATerminalOneAndCarriesNoLook() {
		Workflow workflow = workflow();

		assertThat(workflow.ref(workflow.status("NEW").orElseThrow())).isEqualTo(new StatusRef("NEW", "New", true, false));
		assertThat(workflow.ref(workflow.status("APPROVED").orElseThrow())).isEqualTo(new StatusRef("APPROVED", "Approved", false, false));
		assertThat(workflow.ref(workflow.status("DONE").orElseThrow())).isEqualTo(new StatusRef("DONE", "Done", false, true));
	}

	@Test
	void forwardMovesFollowTheTransitionsAndBackwardMovesNeedTheFlag() {
		Workflow workflow = workflow();

		assertThat(workflow.canMove("NEW", "APPROVED")).isTrue();
		assertThat(workflow.canMove("APPROVED", "NEW")).as("reversible").isTrue();
		assertThat(workflow.canMove("APPROVED", "DONE")).isTrue();
		assertThat(workflow.canMove("DONE", "APPROVED")).as("this transition is not reversible").isFalse();
	}

	@Test
	void statusesThatAreNotConnectedCannotBeSkippedTo() {
		Workflow workflow = workflow();

		assertThat(workflow.canMove("NEW", "DONE")).isFalse();
		assertThat(workflow.canMove("DONE", "NEW")).isFalse();
		assertThat(workflow.canMove("NEW", "NEW")).as("staying is not a move").isFalse();
		assertThat(workflow.canMove("NEW", "MISSING")).isFalse();
		assertThat(workflow.canMove("MISSING", "NEW")).isFalse();
	}

	@Test
	void nothingCanMoveIntoARetiredStatus() {
		Workflow workflow = workflow();

		assertThat(workflow.canMove("DONE", "OLD")).as("configured, but the target is retired").isFalse();
		assertThat(workflow.targetsFrom("DONE")).isEmpty();
	}

	@Test
	void theTargetsAreListedInWorkflowOrder() {
		assertThat(workflow().targetsFrom("APPROVED")).extracting(WorkflowStatus::code).containsExactly("NEW", "DONE");
		assertThat(workflow().targetsFrom("NEW")).extracting(WorkflowStatus::code).containsExactly("APPROVED");
	}

	@Test
	void transitionForFindsTheConfiguredTransitionForwardOrBackwardAndCarriesItsRequiredRole() {
		Workflow restricted = new Workflow(ItemType.PBI,
				java.util.List.of(new WorkflowStatus("NEW", "New", 1, false, true), new WorkflowStatus("DONE", "Done", 2, true, true)),
				java.util.List.of(new WorkflowTransition("NEW", "DONE", true, com.sprintmodus.workitem_service.domain.model.AssignmentRole.PO)));

		assertThat(restricted.transitionFor("NEW", "DONE").orElseThrow().requiredRole())
				.isEqualTo(com.sprintmodus.workitem_service.domain.model.AssignmentRole.PO);
		assertThat(restricted.transitionFor("DONE", "NEW").orElseThrow().requiredRole()).as("the backward move needs the same role")
				.isEqualTo(com.sprintmodus.workitem_service.domain.model.AssignmentRole.PO);
		assertThat(restricted.transitionFor("NEW", "MISSING")).isEmpty();
		assertThat(workflow().transitionFor("NEW", "APPROVED").orElseThrow().requiredRole()).as("unrestricted by default").isNull();
	}

	// ------------------------------------------------------------------ audit diff

	private static WorkItem item() {
		return new WorkItem(UUID.randomUUID(), 1000, UUID.randomUUID(), "WAR", ItemType.PBI, "Title", "Description", "Criteria", Priority.MEDIUM,
				new StatusRef("NEW", "New", true, false), null, null, 5, new BigDecimal("8.00"), new BigDecimal("4"), new UserRef(UUID.randomUUID(), "Ana"),
				null, Fakes.NOW, Fakes.NOW);
	}

	private static WorkItemDiff.After same(WorkItem item) {
		return new WorkItemDiff.After(item.title(), item.description(), item.acceptanceCriteria(), item.priority(), item.effortPoints(),
				item.estimatedHours(), item.remainingHours());
	}

	@Test
	void anEditThatChangesNothingWritesNoAuditEntries() {
		WorkItem item = item();

		assertThat(WorkItemDiff.between(item, same(item), UUID.randomUUID())).isEmpty();
	}

	@Test
	void eachChangedFieldGetsItsOwnEntryOfTheRightType() {
		WorkItem item = item();
		UUID actor = UUID.randomUUID();
		var after = new WorkItemDiff.After("New title", "New description", null, Priority.HIGH, 8, new BigDecimal("6.5"), new BigDecimal("4"));

		var entries = WorkItemDiff.between(item, after, actor);

		assertThat(entries).extracting(AuditEntry::type, AuditEntry::field, AuditEntry::oldValue, AuditEntry::newValue).containsExactly(
				org.assertj.core.groups.Tuple.tuple(ChangeType.FIELD_CHANGED, "Title", "Title", "New title"),
				org.assertj.core.groups.Tuple.tuple(ChangeType.DESCRIPTION_EDITED, "Description", "Description", "New description"),
				org.assertj.core.groups.Tuple.tuple(ChangeType.FIELD_CHANGED, "AcceptanceCriteria", "Criteria", null),
				org.assertj.core.groups.Tuple.tuple(ChangeType.FIELD_CHANGED, "Priority", "MEDIUM", "HIGH"),
				org.assertj.core.groups.Tuple.tuple(ChangeType.EFFORT_CHANGED, "EffortPoints", "5", "8"),
				org.assertj.core.groups.Tuple.tuple(ChangeType.EFFORT_CHANGED, "EstimatedHours", "8", "6.5"));
		assertThat(entries).allSatisfy(entry -> {
			assertThat(entry.changedBy()).isEqualTo(actor);
			assertThat(entry.workItemCode()).isEqualTo(item.code());
		});
	}

	@Test
	void eightHoursAndEightPointZeroZeroAreTheSameNumber() {
		WorkItem item = item();
		var after = new WorkItemDiff.After(item.title(), item.description(), item.acceptanceCriteria(), item.priority(), item.effortPoints(),
				new BigDecimal("8"), new BigDecimal("4.00"));

		assertThat(WorkItemDiff.between(item, after, UUID.randomUUID())).isEmpty();
	}

	@Test
	void longTextIsCutInTheHistoryButNotInTheItem() {
		WorkItem item = item();
		String longText = "x".repeat(2000);
		var after = new WorkItemDiff.After(item.title(), longText, item.acceptanceCriteria(), item.priority(), item.effortPoints(),
				item.estimatedHours(), item.remainingHours());

		var entry = WorkItemDiff.between(item, after, UUID.randomUUID()).getFirst();

		assertThat(entry.newValue()).hasSize(500);
	}

	@Test
	void onlyOwnersAndAdminsAdminister() {
		assertThat(OrganizationRole.OWNER.canAdminister()).isTrue();
		assertThat(OrganizationRole.ADMIN.canAdminister()).isTrue();
		assertThat(OrganizationRole.MEMBER.canAdminister()).isFalse();
		assertThat(OrganizationRole.parse("SUPERUSER")).isEqualTo(OrganizationRole.MEMBER);
		assertThat(OrganizationRole.parse(null)).isEqualTo(OrganizationRole.MEMBER);
	}

	@Test
	void theDisplayKeyCombinesTheProjectKeyAndTheNumber() {
		assertThat(item().displayKey()).isEqualTo("WAR-1000");
	}

}
