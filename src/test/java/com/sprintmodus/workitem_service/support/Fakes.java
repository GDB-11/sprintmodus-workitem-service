package com.sprintmodus.workitem_service.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;
import com.sprintmodus.workitem_service.application.port.external.DomainEventPublisher;
import com.sprintmodus.workitem_service.application.port.external.ProjectServiceGateway;
import com.sprintmodus.workitem_service.application.port.external.TransactionRunner;
import com.sprintmodus.workitem_service.application.port.persistence.AssignmentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.AuditRepository;
import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.application.port.persistence.LinkRepository;
import com.sprintmodus.workitem_service.application.port.persistence.NotificationRepository;
import com.sprintmodus.workitem_service.application.port.persistence.UserRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkItemRepository;
import com.sprintmodus.workitem_service.application.port.persistence.WorkflowRepository;
import com.sprintmodus.workitem_service.domain.event.DomainEvent;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.AuditEntry;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.HistoryEntry;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.Notification;
import com.sprintmodus.workitem_service.domain.model.NotificationType;
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

		/** Items to report as soft-deleted when their history is read. */
		public final Set<UUID> deleted = new HashSet<>();

		/** Time of the next entry read back: each entry is one second after the previous one. */
		private final Instant start = Instant.parse("2026-09-25T10:00:00Z");

		@Override
		public void append(AuditEntry entry) {
			entries.add(entry);
		}

		/** An item exists for the history if anything was ever written for it (every item has its CREATED entry). */
		@Override
		public Optional<History> history(UUID workItemCode, int page, int size) {
			List<HistoryEntry> mine = new ArrayList<>();
			for (int i = 0; i < entries.size(); i++) {
				AuditEntry entry = entries.get(i);
				if (entry.workItemCode().equals(workItemCode)) {
					mine.add(new HistoryEntry(entry.type(), entry.field(), entry.oldValue(), entry.newValue(), entry.additionalData(),
							new UserRef(entry.changedBy(), "User " + entry.changedBy()), start.plusSeconds(i)));
				}
			}
			if (mine.isEmpty()) {
				return Optional.empty();
			}
			List<HistoryEntry> newestFirst = mine.reversed();
			int from = Math.min(page * size, newestFirst.size());
			int to = Math.min(from + size, newestFirst.size());
			return Optional.of(new History(!deleted.contains(workItemCode), List.copyOf(newestFirst.subList(from, to)), mine.size()));
		}

		public List<String> types() {
			return entries.stream().map(entry -> entry.type().name()).toList();
		}

	}

	/** Runs the work directly; counts transactions so tests can see that the change and its audit share one. */
	public static class Transactions implements TransactionRunner {

		public int runs;

		/** True while the work of a transaction runs, so a test can see that something happened after the commit. */
		public boolean active;

		/** Entries appended during each transaction, in order, to check the audit is written inside it. */
		public final List<Integer> auditSizeAtEnd = new ArrayList<>();

		private final Audit audit;

		public Transactions(Audit audit) {
			this.audit = audit;
		}

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			runs++;
			active = true;
			T result;
			try {
				result = work.get();
			}
			finally {
				active = false;
			}
			auditSizeAtEnd.add(audit.entries.size());
			return result;
		}

	}

	/** Keeps the events it is given, and whether each was published while a transaction was still open. */
	public static class Events implements DomainEventPublisher {

		public final List<DomainEvent> published = new ArrayList<>();

		public final List<Boolean> insideTransaction = new ArrayList<>();

		private final Transactions transactions;

		public Events(Transactions transactions) {
			this.transactions = transactions;
		}

		@Override
		public void publish(DomainEvent event) {
			published.add(event);
			insideTransaction.add(transactions.active);
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
		public List<UUID> findActiveDescendantCodes(UUID code) {
			List<UUID> found = new java.util.ArrayList<>();
			List<UUID> level = List.of(code);
			while (!level.isEmpty()) {
				List<UUID> next = new java.util.ArrayList<>();
				for (UUID parent : level) {
					findChildren(parent).forEach(child -> next.add(child.code()));
				}
				found.addAll(next);
				level = next;
			}
			return found;
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

		/** Who each comment mentions, by comment code. */
		public final Map<UUID, List<UUID>> mentions = new HashMap<>();

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

		@Override
		public void addMentions(UUID commentCode, java.util.Collection<UUID> userCodes) {
			mentions.computeIfAbsent(commentCode, _ -> new ArrayList<>()).addAll(userCodes);
		}

	}

	/** The tenant's users as the mention validation and the autocomplete see them. */
	public static class Users implements UserRepository {

		private final Map<UUID, String> names = new java.util.LinkedHashMap<>();

		private final Set<UUID> inactive = new HashSet<>();

		public UUID add(String fullName) {
			UUID code = UUID.randomUUID();
			names.put(code, fullName);
			return code;
		}

		public final Map<String, UUID> emails = new HashMap<>();

		public void add(UUID code, String fullName, String email) {
			names.put(code, fullName);
			emails.put(email.toLowerCase(java.util.Locale.ROOT), code);
		}

		public void deactivate(UUID code) {
			inactive.add(code);
		}

		@Override
		public java.util.Optional<UserRef> findByEmail(String email) {
			UUID code = emails.get(email.strip().toLowerCase(java.util.Locale.ROOT));
			return code == null || inactive.contains(code) ? java.util.Optional.empty() : java.util.Optional.of(new UserRef(code, names.get(code)));
		}

		@Override
		public List<UserRef> findByFullName(String fullName) {
			return names.entrySet().stream().filter(entry -> !inactive.contains(entry.getKey()))
					.filter(entry -> entry.getValue().equalsIgnoreCase(fullName.strip())).limit(2)
					.map(entry -> new UserRef(entry.getKey(), entry.getValue())).toList();
		}

		public String nameOf(UUID code) {
			return names.get(code);
		}

		@Override
		public List<UserRef> findActive(java.util.Collection<UUID> userCodes) {
			return userCodes.stream().filter(names::containsKey).filter(code -> !inactive.contains(code)).distinct()
					.map(code -> new UserRef(code, names.get(code))).toList();
		}

		@Override
		public List<UserRef> search(String query, int limit) {
			String text = query.toLowerCase(java.util.Locale.ROOT);
			return names.entrySet().stream().filter(entry -> !inactive.contains(entry.getKey()))
					.filter(entry -> java.util.Arrays.stream(entry.getValue().toLowerCase(java.util.Locale.ROOT).split(" ")).anyMatch(word -> word.startsWith(text))
							|| entry.getValue().toLowerCase(java.util.Locale.ROOT).startsWith(text))
					.sorted(java.util.Map.Entry.comparingByValue()).limit(limit).map(entry -> new UserRef(entry.getKey(), entry.getValue())).toList();
		}

		@Override
		public int countActive() {
			return (int) names.keySet().stream().filter(code -> !inactive.contains(code)).count();
		}

	}

	/** Notifications of every recipient; remembers whether each was written while a transaction was open. */
	public static class Notifications implements NotificationRepository {

		private record Row(UUID recipient, Notification notification, boolean itemActive) {
		}

		private final List<Row> rows = new ArrayList<>();

		/** Whether each notification was created inside a transaction, in creation order. */
		public final List<Boolean> createdInsideTransaction = new ArrayList<>();

		private final WorkItems items;

		private final Users users;

		private final Transactions transactions;

		private Instant clock = NOW;

		public Notifications(WorkItems items, Users users, Transactions transactions) {
			this.items = items;
			this.users = users;
			this.transactions = transactions;
		}

		/** Every notification of a recipient, newest first, whatever its item. */
		public List<Notification> of(UUID recipient) {
			return rows.stream().filter(row -> row.recipient().equals(recipient)).map(Row::notification).sorted(java.util.Comparator.comparing(Notification::createdAt).reversed()).toList();
		}

		public int total() {
			return rows.size();
		}

		@Override
		public Optional<Notification> create(NotificationType type, UUID recipientCode, UUID workItemCode, UUID commentCode, UUID actorCode) {
			var item = items.findByCode(workItemCode);
			if (item.isEmpty() || users.nameOf(recipientCode) == null) {
				return Optional.empty();
			}
			boolean twice = rows.stream().anyMatch(row -> row.recipient().equals(recipientCode) && row.notification().type() == type
					&& java.util.Objects.equals(row.notification().commentCode(), commentCode));
			if (twice) {
				throw new IllegalStateException("uk_notification_once");
			}
			clock = clock.plusSeconds(1);
			Notification notification = new Notification(UUID.randomUUID(), type, workItemCode, item.get().displayKey(), item.get().title(), commentCode,
					new UserRef(actorCode, users.nameOf(actorCode)), clock, null);
			rows.add(new Row(recipientCode, notification, true));
			createdInsideTransaction.add(transactions.active);
			return Optional.of(notification);
		}

		private List<Row> visible(UUID recipient) {
			return rows.stream().filter(row -> row.recipient().equals(recipient) && row.itemActive() && items.stored.containsKey(row.notification().workItemCode())).toList();
		}

		@Override
		public Page list(UUID recipientCode, boolean unreadOnly, int page, int size) {
			List<Notification> matching = visible(recipientCode).stream().map(Row::notification).filter(n -> !unreadOnly || !n.read())
					.sorted(java.util.Comparator.comparing(Notification::createdAt).reversed()).toList();
			int from = Math.min(page * size, matching.size());
			return new Page(matching.subList(from, Math.min(from + size, matching.size())), matching.size());
		}

		@Override
		public int countUnread(UUID recipientCode) {
			return (int) visible(recipientCode).stream().filter(row -> !row.notification().read()).count();
		}

		@Override
		public boolean markRead(UUID recipientCode, UUID notificationCode) {
			for (int i = 0; i < rows.size(); i++) {
				Row row = rows.get(i);
				if (row.recipient().equals(recipientCode) && row.notification().code().equals(notificationCode) && visible(recipientCode).contains(row)) {
					if (!row.notification().read()) {
						Notification n = row.notification();
						rows.set(i, new Row(row.recipient(), new Notification(n.code(), n.type(), n.workItemCode(), n.workItemKey(), n.workItemTitle(),
								n.commentCode(), n.actor(), n.createdAt(), NOW.plusSeconds(3600)), true));
					}
					return true;
				}
			}
			return false;
		}

		@Override
		public int markAllRead(UUID recipientCode) {
			int marked = 0;
			for (Row row : List.copyOf(visible(recipientCode))) {
				if (!row.notification().read()) {
					markRead(recipientCode, row.notification().code());
					marked++;
				}
			}
			return marked;
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
		public Result<List<SprintInfo>, Failure> listSprints(UUID projectCode) {
			if (down) {
				return Result.failure(Failure.UNAVAILABLE);
			}
			return Result.success(sprints.values().stream().filter(sprint -> sprint.projectCode().equals(projectCode)).toList());
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
