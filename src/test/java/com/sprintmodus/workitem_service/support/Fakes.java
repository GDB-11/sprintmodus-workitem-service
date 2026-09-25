package com.sprintmodus.workitem_service.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.domain.model.Workflow;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowStatus;
import com.sprintmodus.workitem_service.domain.model.Workflow.WorkflowTransition;

/** In-memory implementations of the ports, so use cases can be tested without Spring or a database. */
public final class Fakes {

	/** The status of a workflow as an item sees it. */
	static com.sprintmodus.workitem_service.domain.model.StatusRef refOf(com.sprintmodus.workitem_service.domain.model.Workflow workflow, String code) {
		return workflow.ref(workflow.status(code).orElseThrow());
	}

	private Fakes() {
	}

	public static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

	/** A small workflow: NEW -> ACTIVE -> DONE(terminal), each step reversible, except that DONE cannot go back to NEW directly. */
	public static Workflow simpleWorkflow(ItemType type) {
		return new Workflow(type,
				List.of(new WorkflowStatus("NEW", "New", 1, false, true), new WorkflowStatus("ACTIVE", "Active", 2, false, true),
						new WorkflowStatus("DONE", "Done", 3, true, true)),
				List.of(new WorkflowTransition("NEW", "ACTIVE", true), new WorkflowTransition("ACTIVE", "DONE", false)));
	}

	public static class Workflows implements WorkflowRepository {

		public final Map<ItemType, Workflow> byType = new HashMap<>();

		public final List<StatusUsage> usage = new ArrayList<>();

		public Workflows() {
			for (ItemType type : ItemType.values()) {
				byType.put(type, simpleWorkflow(type));
			}
		}

		@Override
		public Workflow find(ItemType itemType) {
			return byType.getOrDefault(itemType, new Workflow(itemType, List.of(), List.of()));
		}

		@Override
		public void replace(Workflow workflow) {
			byType.put(workflow.itemType(), workflow);
		}

		@Override
		public List<StatusUsage> usage(ItemType itemType) {
			return List.copyOf(usage);
		}

	}

	public static class Audit implements AuditRepository {

		public final List<AuditEntry> entries = new ArrayList<>();

		@Override
		public void append(AuditEntry entry) {
			entries.add(entry);
		}

		public List<String> types() {
			return entries.stream().map(entry -> entry.type().name()).toList();
		}

	}

	/** Runs the work directly; counts transactions so tests can see that the change and its audit share one. */
	public static class Transactions implements TransactionRunner {

		public int runs;

		/** Entries appended during each transaction, in order, to check the audit is written inside it. */
		public final List<Integer> auditSizeAtEnd = new ArrayList<>();

		private final Audit audit;

		public Transactions(Audit audit) {
			this.audit = audit;
		}

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			runs++;
			T result = work.get();
			auditSizeAtEnd.add(audit.entries.size());
			return result;
		}

	}

	public static class WorkItems implements WorkItemRepository {

		public final Map<UUID, String> projectKeys = new HashMap<>();

		public final Map<UUID, WorkItem> stored = new HashMap<>();

		private final Map<UUID, Long> nextNumber = new HashMap<>();

		private final Workflows workflows;

		/** Priority first (CRITICAL first), then the manual rank (unranked last), then the number: a board column's order. */
		private static final java.util.Comparator<WorkItem> BOARD_ORDER = java.util.Comparator
				.<WorkItem, Integer>comparing(item -> -item.priority().ordinal())
				.thenComparing(item -> item.boardRank() == null ? Integer.MAX_VALUE : item.boardRank())
				.thenComparingLong(WorkItem::number);

		/** When set, {@link #changeStatus} loses the race: someone else already moved the item. */
		public boolean statusRace;

		public WorkItems(Workflows workflows) {
			this.workflows = workflows;
		}

		public UUID addProject(String key) {
			UUID code = UUID.randomUUID();
			projectKeys.put(code, key);
			return code;
		}

		@Override
		public Optional<WorkItem> insert(NewWorkItem item) {
			String key = projectKeys.get(item.projectCode());
			if (key == null) {
				return Optional.empty();
			}
			long number = nextNumber.merge(item.projectCode(), 1L, Long::sum) + 999;
			var status = refOf(workflows.find(item.type()), item.initialStatusCode());
			WorkItem created = new WorkItem(UUID.randomUUID(), number, item.projectCode(), key, item.type(), item.title(), item.description(),
					item.acceptanceCriteria(), item.priority(), status, null, item.parentCode(), item.effortPoints(), item.estimatedHours(),
					item.remainingHours(), new UserRef(item.createdBy(), "Creator"), null, NOW, NOW);
			stored.put(created.code(), created);
			return Optional.of(created);
		}

		/** Adds an item directly, for tests that need a starting state. */
		public WorkItem add(UUID projectCode, ItemType type, String status, int points, UUID sprint, UUID createdBy) {
			long number = nextNumber.merge(projectCode, 1L, Long::sum) + 999;
			WorkItem item = new WorkItem(UUID.randomUUID(), number, projectCode, projectKeys.get(projectCode), type, "Item " + number, null, null,
					com.sprintmodus.workitem_service.domain.model.Priority.MEDIUM, refOf(workflows.find(type), status), sprint,
					null, points, BigDecimal.ZERO, BigDecimal.ZERO, new UserRef(createdBy, "Creator"), null, NOW, NOW);
			stored.put(item.code(), item);
			return item;
		}

		/** The same item with another rank, as the database column would hold it. */
		public static WorkItem withRank(WorkItem item, Integer rank) {
			return new WorkItem(item.code(), item.number(), item.projectCode(), item.projectKey(), item.type(), item.title(), item.description(),
					item.acceptanceCriteria(), item.priority(), item.status(), item.sprintCode(), item.parentCode(), item.effortPoints(),
					item.estimatedHours(), item.remainingHours(), item.createdBy(), item.updatedBy(), item.createdAt(), item.updatedAt(), rank,
					item.childCount());
		}

		private void replace(WorkItem before, java.util.function.UnaryOperator<WorkItem> change) {
			stored.put(before.code(), change.apply(before));
		}

		@Override
		public Optional<WorkItem> findByCode(UUID code) {
			return Optional.ofNullable(stored.get(code));
		}

		@Override
		public List<WorkItem> findChildren(UUID code) {
			return stored.values().stream().filter(item -> code.equals(item.parentCode())).toList();
		}

		@Override
		public Page search(Filter filter, int page, int size) {
			List<WorkItem> matches = stored.values().stream()
					.filter(item -> filter.projectCode() == null || item.projectCode().equals(filter.projectCode()))
					.filter(item -> filter.type() == null || item.type() == filter.type())
					.filter(item -> filter.statusCode() == null || item.status().code().equals(filter.statusCode()))
					.filter(item -> switch (filter.sprintScope()) {
						case ANY -> true;
						case BACKLOG -> item.sprintCode() == null;
						case SPRINT -> filter.sprintCode().equals(item.sprintCode());
					})
					.filter(item -> filter.text() == null || filter.text().isBlank()
							|| (item.title() + " " + (item.description() == null ? "" : item.description()))
									.toLowerCase(java.util.Locale.ROOT).contains(filter.text().toLowerCase(java.util.Locale.ROOT)))
					.sorted(filter.boardOrder() ? BOARD_ORDER : (a, b) -> Long.compare(b.number(), a.number())).toList();
			int from = Math.min(page * size, matches.size());
			return new Page(matches.subList(from, Math.min(from + size, matches.size())), matches.size());
		}

		@Override
		public boolean updateFields(UUID code, FieldChanges changes, UUID updatedBy) {
			WorkItem before = stored.get(code);
			if (before == null) {
				return false;
			}
			replace(before, item -> new WorkItem(item.code(), item.number(), item.projectCode(), item.projectKey(), item.type(), changes.title(),
					changes.description(), changes.acceptanceCriteria(), changes.priority(), item.status(), item.sprintCode(), item.parentCode(),
					changes.effortPoints(), changes.estimatedHours(), changes.remainingHours(), item.createdBy(), new UserRef(updatedBy, "Updater"),
					item.createdAt(), NOW, changes.priority() == item.priority() ? item.boardRank() : null, item.childCount()));
			return true;
		}

		@Override
		public boolean setParent(UUID code, UUID parentCode, UUID updatedBy) {
			WorkItem before = stored.get(code);
			if (before == null) {
				return false;
			}
			replace(before, item -> new WorkItem(item.code(), item.number(), item.projectCode(), item.projectKey(), item.type(), item.title(),
					item.description(), item.acceptanceCriteria(), item.priority(), item.status(), item.sprintCode(), parentCode,
					item.effortPoints(), item.estimatedHours(), item.remainingHours(), item.createdBy(), item.updatedBy(), item.createdAt(), NOW));
			return true;
		}

		@Override
		public boolean changeStatus(UUID code, String fromStatusCode, String toStatusCode, UUID updatedBy) {
			WorkItem before = stored.get(code);
			if (before == null || statusRace || !before.status().code().equals(fromStatusCode)) {
				return false;
			}
			var status = refOf(workflows.find(before.type()), toStatusCode);
			replace(before, item -> new WorkItem(item.code(), item.number(), item.projectCode(), item.projectKey(), item.type(), item.title(),
					item.description(), item.acceptanceCriteria(), item.priority(), status, item.sprintCode(), item.parentCode(), item.effortPoints(),
					item.estimatedHours(), item.remainingHours(), item.createdBy(), item.updatedBy(), item.createdAt(), NOW, null, item.childCount()));
			return true;
		}

		@Override
		public boolean setSprint(UUID code, UUID sprintCode, UUID updatedBy) {
			WorkItem before = stored.get(code);
			if (before == null) {
				return false;
			}
			replace(before, item -> new WorkItem(item.code(), item.number(), item.projectCode(), item.projectKey(), item.type(), item.title(),
					item.description(), item.acceptanceCriteria(), item.priority(), item.status(), sprintCode, item.parentCode(), item.effortPoints(),
					item.estimatedHours(), item.remainingHours(), item.createdBy(), item.updatedBy(), item.createdAt(), NOW));
			return true;
		}

		@Override
		public boolean softDelete(UUID code, UUID deletedBy) {
			return stored.remove(code) != null;
		}

		@Override
		public List<UUID> lockRankGroup(UUID projectCode, ItemType type, String statusCode, com.sprintmodus.workitem_service.domain.model.Priority priority) {
			return stored.values().stream().filter(item -> item.projectCode().equals(projectCode) && item.type() == type
					&& item.status().code().equals(statusCode) && item.priority() == priority)
					.sorted(java.util.Comparator.<WorkItem, Integer>comparing(item -> item.boardRank() == null ? Integer.MAX_VALUE : item.boardRank())
							.thenComparingLong(WorkItem::number))
					.map(WorkItem::code).toList();
		}

		@Override
		public boolean setBoardRank(UUID code, int rank) {
			WorkItem before = stored.get(code);
			if (before == null || Integer.valueOf(rank).equals(before.boardRank())) {
				return false;
			}
			stored.put(code, withRank(before, rank));
			return true;
		}

		@Override
		public boolean isSelfOrDescendant(UUID ancestorCode, UUID candidateCode) {
			UUID current = candidateCode;
			while (current != null) {
				if (current.equals(ancestorCode)) {
					return true;
				}
				WorkItem item = stored.get(current);
				current = item == null ? null : item.parentCode();
			}
			return false;
		}

		@Override
		public int countActiveChildren(UUID code) {
			return findChildren(code).size();
		}

		@Override
		public int completedEffortOfSprint(UUID sprintCode) {
			return stored.values().stream().filter(item -> sprintCode.equals(item.sprintCode()))
					.filter(item -> item.type() == ItemType.PBI || item.type() == ItemType.BUG).filter(item -> item.status().terminal())
					.mapToInt(WorkItem::effortPoints).sum();
		}

	}

	/** Remembers which sprints a snapshot was asked for, in order; can be told to fail. */
	public static class Burndown implements com.sprintmodus.workitem_service.application.port.persistence.BurndownRepository {

		public final List<UUID> snapshots = new ArrayList<>();

		public boolean failing;

		@Override
		public void snapshot(UUID sprintCode) {
			if (failing) {
				throw new IllegalStateException("database down");
			}
			snapshots.add(sprintCode);
		}

	}

	public static class Assignments implements AssignmentRepository {

		public final Map<UUID, String> users = new HashMap<>();

		public final List<Assignment> stored = new ArrayList<>();

		public final Map<UUID, UUID> itemOfAssignment = new HashMap<>();

		private final WorkItems items;

		public Assignments(WorkItems items) {
			this.items = items;
		}

		public UUID addUser(String fullName) {
			UUID code = UUID.randomUUID();
			users.put(code, fullName);
			return code;
		}

		@Override
		public List<Assignment> findByWorkItem(UUID workItemCode) {
			return stored.stream().filter(assignment -> workItemCode.equals(itemOfAssignment.get(assignment.code()))).toList();
		}

		@Override
		public java.util.Map<UUID, List<Assignment>> findByWorkItems(java.util.Collection<UUID> workItemCodes) {
			java.util.Map<UUID, List<Assignment>> found = new HashMap<>();
			for (UUID code : workItemCodes) {
				List<Assignment> assigned = findByWorkItem(code);
				if (!assigned.isEmpty()) {
					found.put(code, assigned);
				}
			}
			return found;
		}

		@Override
		public Optional<Assignment> find(UUID workItemCode, UUID assignmentCode) {
			return findByWorkItem(workItemCode).stream().filter(assignment -> assignment.code().equals(assignmentCode)).findFirst();
		}

		@Override
		public boolean isAssigned(UUID workItemCode, UUID userCode, AssignmentRole role) {
			return findByWorkItem(workItemCode).stream().anyMatch(a -> a.userCode().equals(userCode) && a.role() == role);
		}

		@Override
		public Optional<Assignment> assign(UUID workItemCode, UUID userCode, AssignmentRole role) {
			if (items.findByCode(workItemCode).isEmpty() || !users.containsKey(userCode)) {
				return Optional.empty();
			}
			Assignment assignment = new Assignment(UUID.randomUUID(), userCode, users.get(userCode), role);
			stored.add(assignment);
			itemOfAssignment.put(assignment.code(), workItemCode);
			return Optional.of(assignment);
		}

		@Override
		public boolean unassign(UUID workItemCode, UUID assignmentCode) {
			Optional<Assignment> found = find(workItemCode, assignmentCode);
			found.ifPresent(stored::remove);
			return found.isPresent();
		}

	}

	public static class Links implements LinkRepository {

		private record Edge(UUID code, UUID source, UUID target, LinkType type, boolean active) {
		}

		private final List<Edge> stored = new ArrayList<>();

		private final WorkItems items;

		public Links(WorkItems items) {
			this.items = items;
		}

		@Override
		public List<Link> findByWorkItem(UUID workItemCode) {
			return stored.stream().filter(e -> e.active() && (e.source().equals(workItemCode) || e.target().equals(workItemCode)))
					.map(e -> toLink(e, workItemCode)).toList();
		}

		@Override
		public Optional<Link> find(UUID workItemCode, UUID linkCode) {
			return stored.stream()
					.filter(e -> e.active() && e.code().equals(linkCode) && (e.source().equals(workItemCode) || e.target().equals(workItemCode)))
					.findFirst().map(e -> toLink(e, workItemCode));
		}

		@Override
		public boolean exists(UUID sourceCode, UUID targetCode, LinkType type) {
			return stored.stream().anyMatch(e -> e.active() && e.source().equals(sourceCode) && e.target().equals(targetCode) && e.type() == type);
		}

		@Override
		public boolean wouldCreateCycle(UUID sourceCode, UUID targetCode, LinkType type) {
			java.util.Set<UUID> visited = new java.util.HashSet<>();
			java.util.Deque<UUID> queue = new java.util.ArrayDeque<>(List.of(targetCode));
			while (!queue.isEmpty()) {
				UUID current = queue.poll();
				if (!visited.add(current)) {
					continue;
				}
				if (current.equals(sourceCode)) {
					return true;
				}
				stored.stream().filter(e -> e.active() && e.type() == type && e.source().equals(current))
						.forEach(e -> queue.add(e.target()));
			}
			return false;
		}

		@Override
		public Optional<Link> create(UUID sourceCode, UUID targetCode, LinkType type, UUID createdBy) {
			if (items.findByCode(sourceCode).isEmpty() || items.findByCode(targetCode).isEmpty()) {
				return Optional.empty();
			}
			for (int i = 0; i < stored.size(); i++) {
				Edge e = stored.get(i);
				if (e.source().equals(sourceCode) && e.target().equals(targetCode) && e.type() == type) {
					Edge reactivated = new Edge(e.code(), sourceCode, targetCode, type, true);
					stored.set(i, reactivated);
					return Optional.of(toLink(reactivated, sourceCode));
				}
			}
			Edge created = new Edge(UUID.randomUUID(), sourceCode, targetCode, type, true);
			stored.add(created);
			return Optional.of(toLink(created, sourceCode));
		}

		@Override
		public boolean unlink(UUID workItemCode, UUID linkCode) {
			for (int i = 0; i < stored.size(); i++) {
				Edge e = stored.get(i);
				if (e.code().equals(linkCode) && e.active() && (e.source().equals(workItemCode) || e.target().equals(workItemCode))) {
					stored.set(i, new Edge(e.code(), e.source(), e.target(), e.type(), false));
					return true;
				}
			}
			return false;
		}

		private Link toLink(Edge e, UUID viewpoint) {
			UUID otherCode = e.source().equals(viewpoint) ? e.target() : e.source();
			return new Link(e.code(), e.type(), items.findByCode(otherCode).orElseThrow());
		}

	}

	public static class Comments implements CommentRepository {

		public final List<Comment> stored = new ArrayList<>();

		private final WorkItems items;

		public Comments(WorkItems items) {
			this.items = items;
		}

		@Override
		public List<Comment> findByWorkItem(UUID workItemCode) {
			return stored.stream().filter(comment -> comment.workItemCode().equals(workItemCode)).toList();
		}

		@Override
		public Optional<Comment> add(UUID workItemCode, UUID authorCode, String content) {
			if (items.findByCode(workItemCode).isEmpty()) {
				return Optional.empty();
			}
			Comment comment = new Comment(UUID.randomUUID(), workItemCode, new UserRef(authorCode, "Author"), content, NOW);
			stored.add(comment);
			return Optional.of(comment);
		}

	}

	/** A project-service that can be told to fail, and remembers the velocity it was given. */
	public static class Projects implements ProjectServiceGateway {

		public final Map<UUID, SprintInfo> sprints = new HashMap<>();

		public final Map<UUID, Integer> velocities = new HashMap<>();

		public final List<UUID> velocityCalls = new ArrayList<>();

		public final Map<UUID, ProjectInfo> projects = new HashMap<>();

		public boolean down;

		public boolean velocityFails;

		public SprintInfo add(UUID projectCode, String name, String status) {
			SprintInfo sprint = new SprintInfo(UUID.randomUUID(), projectCode, name, status);
			sprints.put(sprint.sprintCode(), sprint);
			return sprint;
		}

		@Override
		public Result<ProjectInfo, Failure> getProject(UUID projectCode) {
			if (down) {
				return Result.failure(Failure.UNAVAILABLE);
			}
			ProjectInfo project = projects.get(projectCode);
			return project == null ? Result.failure(Failure.NOT_FOUND) : Result.success(project);
		}

		@Override
		public Result<SprintInfo, Failure> getSprint(UUID sprintCode) {
			if (down) {
				return Result.failure(Failure.UNAVAILABLE);
			}
			SprintInfo sprint = sprints.get(sprintCode);
			return sprint == null ? Result.failure(Failure.NOT_FOUND) : Result.success(sprint);
		}

		@Override
		public Result<Unit, Failure> updateSprintVelocity(UUID sprintCode, int velocity) {
			velocityCalls.add(sprintCode);
			if (down || velocityFails) {
				return Result.failure(Failure.UNAVAILABLE);
			}
			velocities.put(sprintCode, velocity);
			return Result.success(Unit.VALUE);
		}

	}

}
