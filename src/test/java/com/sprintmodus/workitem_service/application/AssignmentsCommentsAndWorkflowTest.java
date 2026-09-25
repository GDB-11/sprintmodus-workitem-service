package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.dto.Commands.Assign;
import com.sprintmodus.workitem_service.application.dto.Commands.UpsertWorkflow;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkflowStatusDefinition;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkflowTransitionDefinition;
import com.sprintmodus.workitem_service.application.error.AssignmentError;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.WorkflowError;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository.StatusUsage;
import com.sprintmodus.workitem_service.application.service.AddCommentUseCase;
import com.sprintmodus.workitem_service.application.service.AssignWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.GetWorkflowUseCase;
import com.sprintmodus.workitem_service.application.service.ListCommentsUseCase;
import com.sprintmodus.workitem_service.application.service.UnassignWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.UpsertWorkflowUseCase;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.support.Fakes;

class AssignmentsCommentsAndWorkflowTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Assignments assignments = new Fakes.Assignments(items);

	private final Fakes.Comments comments = new Fakes.Comments(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final Actor owner = new Actor(UUID.randomUUID(), OrganizationRole.OWNER);

	private WorkItem item;

	@BeforeEach
	void anItem() {
		item = items.add(items.addProject("WAR"), ItemType.PBI, "NEW", 3, null, member.userCode());
	}

	// ------------------------------------------------------------------ assignments

	private final AssignWorkItemUseCase assign = new AssignWorkItemUseCase(items, assignments, audit, transactions);

	private final UnassignWorkItemUseCase unassign = new UnassignWorkItemUseCase(assignments, audit, transactions);

	@Test
	void assignsAUserInARoleAndAuditsItWithTheActingUser() {
		UUID ana = assignments.addUser("Ana Diaz");

		var result = assign.execute(new Assign(member, item.code(), ana, AssignmentRole.DEV));

		assertThat(result.getValue().fullName()).isEqualTo("Ana Diaz");
		assertThat(result.getValue().role()).isEqualTo(AssignmentRole.DEV);
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.ASSIGNED);
			assertThat(entry.newValue()).isEqualTo("Ana Diaz (DEV)");
			assertThat(entry.changedBy()).as("who assigned, not who was assigned").isEqualTo(member.userCode());
			assertThat(entry.additionalData()).containsEntry("userCode", ana.toString()).containsEntry("role", "DEV");
		});
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
	}

	@Test
	void oneUserCanHoldSeveralRolesButEachOnlyOnce() {
		UUID ana = assignments.addUser("Ana Diaz");

		assertThat(assign.execute(new Assign(member, item.code(), ana, AssignmentRole.DEV)).isSuccess()).isTrue();
		assertThat(assign.execute(new Assign(member, item.code(), ana, AssignmentRole.QA)).isSuccess()).isTrue();
		assertThat(assign.execute(new Assign(member, item.code(), ana, AssignmentRole.DEV)).getError()).isInstanceOf(AssignmentError.AlreadyAssigned.class);
		assertThat(assignments.findByWorkItem(item.code())).hasSize(2);
		assertThat(audit.entries).hasSize(2);
	}

	@Test
	void assignmentValidationAndMissingReferences() {
		UUID ana = assignments.addUser("Ana Diaz");

		assertThat(assign.execute(new Assign(member, item.code(), null, AssignmentRole.DEV)).getError()).isInstanceOf(AssignmentError.InvalidAssignment.class);
		assertThat(assign.execute(new Assign(member, item.code(), ana, null)).getError()).isInstanceOf(AssignmentError.InvalidAssignment.class);
		assertThat(assign.execute(new Assign(member, UUID.randomUUID(), ana, AssignmentRole.DEV)).getError()).isInstanceOf(AssignmentError.WorkItemNotFound.class);
		assertThat(assign.execute(new Assign(member, item.code(), UUID.randomUUID(), AssignmentRole.DEV)).getError()).isInstanceOf(AssignmentError.UserNotFound.class);
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void unassigningRemovesTheAssignmentAndAuditsIt() {
		UUID ana = assignments.addUser("Ana Diaz");
		var assignment = assign.execute(new Assign(member, item.code(), ana, AssignmentRole.QA)).getValue();
		audit.entries.clear();

		assertThat(unassign.execute(owner, item.code(), assignment.code()).isSuccess()).isTrue();

		assertThat(assignments.findByWorkItem(item.code())).isEmpty();
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.UNASSIGNED);
			assertThat(entry.oldValue()).isEqualTo("Ana Diaz (QA)");
			assertThat(entry.changedBy()).isEqualTo(owner.userCode());
		});
		assertThat(unassign.execute(owner, item.code(), assignment.code()).getError()).isInstanceOf(AssignmentError.AssignmentNotFound.class);
	}

	@Test
	void anAssignmentOfAnotherItemCannotBeRemovedThroughThisOne() {
		UUID ana = assignments.addUser("Ana Diaz");
		var other = items.add(items.addProject("OTH"), ItemType.TASK, "NEW", 0, null, member.userCode());
		var assignment = assign.execute(new Assign(member, other.code(), ana, AssignmentRole.DEV)).getValue();

		assertThat(unassign.execute(owner, item.code(), assignment.code()).getError()).isInstanceOf(AssignmentError.AssignmentNotFound.class);
		assertThat(assignments.findByWorkItem(other.code())).hasSize(1);
	}

	// ------------------------------------------------------------------ comments

	private final AddCommentUseCase addComment = new AddCommentUseCase(comments, audit, transactions);

	private final ListCommentsUseCase listComments = new ListCommentsUseCase(items, comments);

	@Test
	void aCommentIsStoredAndAuditedInTheSameTransaction() {
		var result = addComment.execute(new AddComment(member, item.code(), "  Looks good to me  "));

		assertThat(result.getValue().content()).isEqualTo("Looks good to me");
		assertThat(result.getValue().author().userCode()).isEqualTo(member.userCode());
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.COMMENTED);
			assertThat(entry.field()).isEqualTo("Comment");
			assertThat(entry.newValue()).isEqualTo("Looks good to me");
			assertThat(entry.changedBy()).isEqualTo(member.userCode());
			assertThat(entry.additionalData()).containsEntry("commentCode", result.getValue().code().toString());
		});
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
	}

	@Test
	void theAuditEntryKeepsOnlyTheFirst100CharactersButTheCommentKeepsAll() {
		String text = "y".repeat(250);

		var result = addComment.execute(new AddComment(member, item.code(), text));

		assertThat(result.getValue().content()).hasSize(250);
		assertThat(audit.entries.getFirst().newValue()).hasSize(100);
	}

	@Test
	void commentValidationAndAMissingItem() {
		for (String content : new String[] { null, "", "   " }) {
			assertThat(addComment.execute(new AddComment(member, item.code(), content)).getError()).isInstanceOf(CommentError.InvalidComment.class);
		}
		assertThat(addComment.execute(new AddComment(member, item.code(), "z".repeat(10_001))).isFailure()).isTrue();
		assertThat(addComment.execute(new AddComment(member, item.code(), "z".repeat(10_000))).isSuccess()).isTrue();
		assertThat(addComment.execute(new AddComment(member, UUID.randomUUID(), "Hi")).getError()).isInstanceOf(CommentError.WorkItemNotFound.class);
		assertThat(audit.entries).as("only the valid comment").hasSize(1);
	}

	@Test
	void commentsAreListedForAnExistingItemOnly() {
		addComment.execute(new AddComment(member, item.code(), "First"));
		addComment.execute(new AddComment(owner, item.code(), "Second"));

		assertThat(listComments.execute(item.code()).getValue()).extracting(comment -> comment.content()).containsExactly("First", "Second");
		assertThat(listComments.execute(UUID.randomUUID()).getError()).isInstanceOf(CommentError.WorkItemNotFound.class);
	}

	// ------------------------------------------------------------------ workflow configuration

	private final UpsertWorkflowUseCase upsert = new UpsertWorkflowUseCase(workflows, transactions);

	private static WorkflowStatusDefinition status(String code, String name, Integer order, boolean terminal) {
		return new WorkflowStatusDefinition(code, name, order, terminal);
	}

	private UpsertWorkflow definition(List<WorkflowStatusDefinition> statuses, List<WorkflowTransitionDefinition> transitions) {
		return new UpsertWorkflow(owner, ItemType.PBI, statuses, transitions);
	}

	private static List<WorkflowStatusDefinition> threeStatuses() {
		return List.of(status("TODO", "To do", 1, false), status("DOING", "Doing", 2, false), status("FINISHED", "Finished", 3, true));
	}

	@Test
	void anAdminReplacesTheWorkflowOfAType() {
		var result = upsert.execute(definition(threeStatuses(),
				List.of(new WorkflowTransitionDefinition("TODO", "DOING", true), new WorkflowTransitionDefinition("DOING", "FINISHED", false))));

		var workflow = result.getValue();
		assertThat(workflow.statuses()).extracting(status -> status.code()).containsExactly("TODO", "DOING", "FINISHED");
		assertThat(workflow.transitions()).hasSize(2);
		assertThat(workflows.find(ItemType.PBI).initialStatus().orElseThrow().code()).isEqualTo("TODO");
		assertThat(workflows.find(ItemType.TASK).status("NEW")).as("other types are untouched").isPresent();
	}

	@Test
	void aTransitionCanNameTheRoleRequiredToMakeItAndItIsNormalized() {
		var result = upsert.execute(definition(threeStatuses(),
				List.of(new WorkflowTransitionDefinition("TODO", "DOING", true, " po "), new WorkflowTransitionDefinition("DOING", "FINISHED", false, null))));

		var transitions = result.getValue().transitions();
		assertThat(transitions.stream().filter(t -> t.from().equals("TODO")).findFirst().orElseThrow().requiredRole()).isEqualTo(AssignmentRole.PO);
		assertThat(transitions.stream().filter(t -> t.from().equals("DOING")).findFirst().orElseThrow().requiredRole())
				.as("no restriction by default").isNull();
	}

	@Test
	void anUnknownRoleOnATransitionIsRejectedAndNothingChanges() {
		var result = upsert.execute(definition(threeStatuses(), List.of(new WorkflowTransitionDefinition("TODO", "DOING", true, "CAPTAIN"))));

		assertThat(result.getError()).isInstanceOf(WorkflowError.InvalidWorkflow.class);
		assertThat(workflows.find(ItemType.PBI).statuses()).extracting(s -> s.code()).containsExactly("NEW", "ACTIVE", "DONE");
	}

	@Test
	void statusesAndTransitionsAreNormalizedAndDefaulted() {
		var result = upsert.execute(definition(
				List.of(new WorkflowStatusDefinition(" todo ", " To do ", null, null), new WorkflowStatusDefinition("done", "Done", null, true)),
				List.of(new WorkflowTransitionDefinition("todo", " DONE ", null))));

		var workflow = result.getValue();
		assertThat(workflow.statuses()).extracting(status -> status.code() + ":" + status.order() + ":" + status.terminal())
				.containsExactly("TODO:1:false", "DONE:2:true");
		assertThat(workflow.transitions().getFirst().allowedBackward()).isFalse();
	}

	@Test
	void onlyOwnersAndAdminsMayChangeAWorkflow() {
		var result = upsert.execute(new UpsertWorkflow(member, ItemType.PBI, threeStatuses(), List.of()));

		assertThat(result.getError()).isInstanceOf(WorkflowError.NotAllowed.class);
		assertThat(workflows.find(ItemType.PBI).statuses()).extracting(s -> s.code()).containsExactly("NEW", "ACTIVE", "DONE");
	}

	@Test
	void invalidWorkflowsAreRejectedAndNothingChanges() {
		var noTerminal = List.of(status("A", "A", 1, false), status("B", "B", 2, false));
		var ok = threeStatuses();
		List<UpsertWorkflow> invalid = List.of(
				new UpsertWorkflow(owner, null, ok, List.of()),
				definition(null, List.of()),
				definition(List.of(), List.of()),
				definition(noTerminal, List.of()),
				definition(List.of(status("in progress", "X", 1, true)), List.of()),
				definition(List.of(status("1ST", "X", 1, true)), List.of()),
				definition(List.of(status("A", "A", 1, false), status("a", "Again", 2, true)), List.of()),
				definition(List.of(status("A", "  ", 1, true)), List.of()),
				definition(List.of(status("A", "A", 0, true)), List.of()),
				definition(ok, List.of(new WorkflowTransitionDefinition("TODO", "NOWHERE", true))),
				definition(ok, List.of(new WorkflowTransitionDefinition("TODO", "TODO", true))),
				definition(ok, List.of(new WorkflowTransitionDefinition("TODO", "DOING", true), new WorkflowTransitionDefinition("todo", "doing", false))));

		for (UpsertWorkflow command : invalid) {
			assertThat(upsert.execute(command).getError()).as(String.valueOf(command)).isInstanceOf(WorkflowError.InvalidWorkflow.class);
		}
		assertThat(workflows.find(ItemType.PBI).statuses()).extracting(s -> s.code()).containsExactly("NEW", "ACTIVE", "DONE");
	}

	@Test
	void aWorkflowHasAtMostFiftyStatuses() {
		List<WorkflowStatusDefinition> many = new java.util.ArrayList<>();
		for (int i = 1; i <= 51; i++) {
			many.add(status("S" + i, "Status " + i, i, i == 51));
		}

		assertThat(upsert.execute(definition(many, List.of())).getError()).isInstanceOf(WorkflowError.InvalidWorkflow.class);
		assertThat(upsert.execute(definition(many.subList(1, 51), List.of())).isSuccess()).isTrue();
	}

	@Test
	void aStatusThatStillHasWorkItemsCannotBeDropped() {
		workflows.usage.add(new StatusUsage("ACTIVE", 2));

		var result = upsert.execute(definition(threeStatuses(), List.of()));

		assertThat(result.getError()).isEqualTo(new WorkflowError.StatusInUse("ACTIVE", 2));
		assertThat(result.getError().message()).isEqualTo("Status ACTIVE still has 2 work items. Move them to another status first.");
		assertThat(workflows.find(ItemType.PBI).statuses()).extracting(s -> s.code()).containsExactly("NEW", "ACTIVE", "DONE");
	}

	@Test
	void aStatusWithWorkItemsMayStayEvenIfItsSettingsChange() {
		workflows.usage.add(new StatusUsage("ACTIVE", 2));

		var result = upsert.execute(definition(List.of(status("NEW", "New", 1, false), status("ACTIVE", "In flight", 2, false), status("DONE", "Done", 3, true)), List.of()));

		assertThat(result.isSuccess()).isTrue();
		assertThat(workflows.find(ItemType.PBI).status("ACTIVE").orElseThrow().displayName()).isEqualTo("In flight");
	}

	@Test
	void anyoneReadsTheWorkflowAndOnlyActiveStatusesAreShown() {
		workflows.replace(new com.sprintmodus.workitem_service.domain.model.Workflow(ItemType.TASK,
				List.of(new com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus("B", "B", 2, true, true),
						new com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus("OLD", "Old", 1, false, false),
						new com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus("A", "A", 0, false, true)),
				List.of()));

		var workflow = new GetWorkflowUseCase(workflows).execute(ItemType.TASK).getValue();

		assertThat(workflow.itemType()).isEqualTo(ItemType.TASK);
		assertThat(workflow.statuses()).extracting(s -> s.code()).as("active only, in order").containsExactly("A", "B");
	}

}
