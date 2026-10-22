package com.sprintmodus.workitem_service.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.error.WorkItemError;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidParent;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.error.WorkItemError.ProjectNotFound;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkItemNotFound;
import com.sprintmodus.workitem_service.application.error.WorkItemError.WorkflowNotConfigured;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway.SprintInfo;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository.NewWorkItem;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic;
import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic.Code;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat;
import com.sprintmodus.workitem_service.domain.markdown.ParseResult;
import com.sprintmodus.workitem_service.domain.markdown.ParsedItem;
import com.sprintmodus.workitem_service.domain.markdown.WorkItemMarkdownParser;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.service.EffortPointsService;

/**
 * Creates the work items of a Markdown file under a project, or under one parent, in a single all-or-nothing
 * transaction (or only previews them, with {@code dryRun}). The file names neither the project nor the parent: the screen
 * does, and decides the type of the roots. Status is never set (items start in their type's initial status) and nothing is
 * invented: a sprint or assignee that cannot be resolved is a warning and the item is still created. Each item gets the
 * same {@code CREATED} audit row as one made by hand.
 */
@Service
public class ImportWorkItemsUseCase {

	/** What was asked for. {@code parentCode} is null when the roots are Epics. */
	public record Command(Actor actor, UUID projectCode, UUID parentCode, String markdown, boolean dryRun) {
	}

	/** A root that was created. */
	public record CreatedRoot(UUID workItemCode, String displayKey, ItemType type, String title) {
	}

	/**
	 * @param imported whether anything was written (never on a dry run, never when there are errors)
	 * @param items the parsed tree, as it would be (or was) created
	 */
	public record Outcome(boolean dryRun, boolean imported, List<ParsedItem> items, List<ImportDiagnostic> diagnostics,
			List<CreatedRoot> created) {

		public boolean hasErrors() {
			return diagnostics.stream().anyMatch(d -> d.severity() == ImportDiagnostic.Severity.ERROR);
		}

	}

	private static final int MAX_TITLE_LENGTH = 500;

	private static final int MAX_TEXT_LENGTH = 50_000;

	private final WorkItemRepository items;

	private final WorkflowRepository workflows;

	private final AuditRepository audit;

	private final AssignmentRepository assignments;

	private final UserRepository users;

	private final ProjectServiceGateway projects;

	private final TransactionRunner transactions;

	private final BurndownRecorder burndown;

	public ImportWorkItemsUseCase(WorkItemRepository items, WorkflowRepository workflows, AuditRepository audit,
			AssignmentRepository assignments, UserRepository users, ProjectServiceGateway projects, TransactionRunner transactions,
			BurndownRecorder burndown) {
		this.items = items;
		this.workflows = workflows;
		this.audit = audit;
		this.assignments = assignments;
		this.users = users;
		this.projects = projects;
		this.transactions = transactions;
		this.burndown = burndown;
	}

	public Result<Outcome, WorkItemError> execute(Command command) {
		if (command.projectCode() == null) {
			return Result.failure(new InvalidWorkItemData("projectCode", "Choose the project to import into."));
		}
		if (command.markdown() == null || command.markdown().isBlank()) {
			return Result.failure(new InvalidWorkItemData("markdown", "The file is empty."));
		}
		ItemType expectedRoot = ItemType.EPIC;
		if (command.parentCode() != null) {
			WorkItem parent = items.findByCode(command.parentCode()).orElse(null);
			if (parent == null) {
				return Result.failure(new WorkItemNotFound());
			}
			if (!parent.projectCode().equals(command.projectCode())) {
				return Result.failure(new InvalidParent("The parent must be in the same project."));
			}
			expectedRoot = MarkdownFormat.rootFor(parent.type()).orElse(null);
			if (expectedRoot == null) {
				return Result.failure(new InvalidParent("Items cannot be imported under a " + parent.type() + "."));
			}
		}
		if (projects.getProject(command.projectCode()).fold(_ -> false, failure -> failure == ProjectServiceGateway.Failure.NOT_FOUND)) {
			return Result.failure(new ProjectNotFound());
		}

		ParseResult parsed = WorkItemMarkdownParser.parse(command.markdown(), expectedRoot);
		List<ImportDiagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
		Resolver resolver = new Resolver(command, diagnostics);
		Plan plan = new Plan();
		if (!parsed.hasErrors()) {
			parsed.roots().forEach(root -> resolver.check(root, plan));
		}
		boolean errors = diagnostics.stream().anyMatch(d -> d.severity() == ImportDiagnostic.Severity.ERROR);
		if (errors || command.dryRun()) {
			return Result.success(new Outcome(command.dryRun(), false, parsed.roots(), List.copyOf(diagnostics), List.of()));
		}
		for (ItemType type : MarkdownFormat.CHAIN) {
			if (plan.types.contains(type) && workflows.find(type).initialStatus().isEmpty()) {
				return Result.failure(new WorkflowNotConfigured());
			}
		}

		List<CreatedRoot> created = new ArrayList<>();
		java.util.Set<UUID> sprints = new java.util.HashSet<>();
		Boolean written = transactions.inTransaction(() -> {
			for (ParsedItem root : parsed.roots()) {
				CreatedRoot createdRoot = create(command, root, command.parentCode(), resolver, sprints);
				if (createdRoot == null) {
					return false;
				}
				created.add(createdRoot);
			}
			return true;
		});
		if (!Boolean.TRUE.equals(written)) {
			// Anything created inside this transaction was rolled back by the failed insert's exception, or never happened
			return Result.failure(new ProjectNotFound());
		}
		burndown.record(sprints.toArray(UUID[]::new));
		return Result.success(new Outcome(false, true, parsed.roots(), List.copyOf(diagnostics), List.copyOf(created)));
	}

	/** Inserts the item and its children depth first. Returns null if the project disappeared. */
	private CreatedRoot create(Command command, ParsedItem parsed, UUID parentCode, Resolver resolver, java.util.Set<UUID> sprints) {
		var initial = workflows.find(parsed.type()).initialStatus().orElseThrow();
		int points = parsed.points() == null ? 0 : parsed.points().setScale(0, RoundingMode.HALF_UP).intValue();
		BigDecimal hours = parsed.hours() == null ? BigDecimal.ZERO : parsed.hours();
		String criteria = parsed.acceptanceCriteria().isEmpty() ? null : String.join("\n", parsed.acceptanceCriteria());
		String description = parsed.description() == null || parsed.description().isBlank() ? null : parsed.description();
		NewWorkItem toInsert = new NewWorkItem(command.projectCode(), parsed.type(), initial.code(), parsed.title(), description, criteria,
				parsed.priority() == null ? Priority.MEDIUM : parsed.priority(), parentCode, points, hours, hours, command.actor().userCode());
		WorkItem item = items.insert(toInsert).orElse(null);
		if (item == null) {
			return null;
		}
		audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.CREATED, null, null, parsed.title(),
				Map.of("displayKey", item.displayKey(), "type", item.type().name(), "status", initial.code(), "source", "markdown-import")));

		SprintInfo sprint = resolver.sprint(parsed);
		if (sprint != null && items.setSprint(item.code(), sprint.sprintCode(), command.actor().userCode())) {
			audit.append(new AuditEntry(item.code(), command.actor().userCode(), ChangeType.SPRINT_CHANGED, "Sprint", null,
					sprint.sprintCode().toString(), Map.of("displayKey", item.displayKey(), "sprint", sprint.name())));
			sprints.add(sprint.sprintCode());
		}
		UserRef assignee = resolver.assignee(parsed);
		if (assignee != null) {
			assignments.assign(item.code(), assignee.userCode(), AssignmentRole.DEV).ifPresent(assignment -> audit.append(
					new AuditEntry(item.code(), command.actor().userCode(), ChangeType.ASSIGNED, null, null, assignment.label(),
							Map.of("displayKey", item.displayKey()))));
		}
		for (ParsedItem child : parsed.children()) {
			if (create(command, child, item.code(), resolver, sprints) == null) {
				return null;
			}
		}
		return new CreatedRoot(item.code(), item.displayKey(), item.type(), item.title());
	}

	private static final class Plan {

		private final java.util.Set<ItemType> types = new java.util.HashSet<>();

	}

	/**
	 * Looks up what the file names (sprints by name, people by e-mail and then exact name) once per distinct value, adds a
	 * warning for each one that does not resolve, and checks the limits only the database knows.
	 */
	private final class Resolver {

		private final Command command;

		private final List<ImportDiagnostic> diagnostics;

		private Map<String, SprintInfo> sprintsByName;

		private final Map<String, UserRef> people = new HashMap<>();

		private final java.util.Set<String> unresolved = new java.util.HashSet<>();

		Resolver(Command command, List<ImportDiagnostic> diagnostics) {
			this.command = command;
			this.diagnostics = diagnostics;
		}

		void check(ParsedItem item, Plan plan) {
			plan.types.add(item.type());
			if (item.title().length() > MAX_TITLE_LENGTH) {
				add(Code.TITLE_TOO_LONG, item.line(), "max", String.valueOf(MAX_TITLE_LENGTH));
			}
			if (item.description() != null && item.description().length() > MAX_TEXT_LENGTH) {
				add(Code.TEXT_TOO_LONG, item.line(), "max", String.valueOf(MAX_TEXT_LENGTH));
			}
			BigDecimal hours = item.hours() == null ? BigDecimal.ZERO : item.hours();
			int points = item.points() == null ? 0 : item.points().setScale(0, RoundingMode.HALF_UP).intValue();
			if (EffortPointsService.validateEffort(points, hours, hours).isFailure()) {
				add(Code.INVALID_NUMBER, item.line(), "field", item.points() != null ? "puntos" : "horas");
			}
			sprint(item);
			assignee(item);
			item.children().forEach(child -> check(child, plan));
		}

		/** The sprint the item is planned in, or null (with a warning once per distinct unresolved name). */
		SprintInfo sprint(ParsedItem item) {
			if (item.sprintName() == null || item.sprintName().isBlank()) {
				return null;
			}
			if (sprintsByName == null) {
				sprintsByName = new HashMap<>();
				projects.listSprints(command.projectCode()).fold(list -> {
					list.stream().filter(sprint -> !sprint.isClosed()).forEach(sprint -> sprintsByName.putIfAbsent(MarkdownFormat.normalize(sprint.name()), sprint));
					return null;
				}, _ -> null);
			}
			SprintInfo sprint = command.actor().canAdminister() ? sprintsByName.get(MarkdownFormat.normalize(item.sprintName())) : null;
			if (sprint == null && unresolved.add("sprint:" + item.line())) {
				add(Code.UNKNOWN_SPRINT, item.line(), "sprint", item.sprintName());
			}
			return sprint;
		}

		UserRef assignee(ParsedItem item) {
			if (item.assignee() == null || item.assignee().isBlank()) {
				return null;
			}
			String key = item.assignee().strip();
			UserRef user = people.computeIfAbsent(key, text -> {
				UserRef byEmail = text.contains("@") ? users.findByEmail(text).orElse(null) : null;
				if (byEmail != null) {
					return byEmail;
				}
				List<UserRef> byName = users.findByFullName(text);
				return byName.size() == 1 ? byName.getFirst() : null;
			});
			if (user == null && unresolved.add("assignee:" + item.line())) {
				add(Code.UNKNOWN_ASSIGNEE, item.line(), "assignee", item.assignee());
			}
			return user;
		}

		private void add(Code code, int line, String key, String value) {
			if (diagnostics.stream().noneMatch(d -> d.code() == code && d.line() == line)) {
				diagnostics.add(new ImportDiagnostic(code, line, Map.of(key, value)));
			}
		}

	}

}
