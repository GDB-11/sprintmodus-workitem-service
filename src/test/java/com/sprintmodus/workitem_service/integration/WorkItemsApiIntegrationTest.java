package com.sprintmodus.workitem_service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sprintmodus.workitem_service.integration.TenantFixtures.Tenant;
import com.sprintmodus.workitem_service.integration.TenantFixtures.User;

/**
 * Definition of Done of Phase 5, through the real security filter, the real native-query repositories and a real MySQL
 * with the real tenant migrations: every work item type can be created, non-standard parents only warn, moving between
 * sprints updates the velocity of both ends, and every change is audited in the transaction that made it. Every test builds
 * its own tenant database.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false" })
@AutoConfigureMockMvc
class WorkItemsApiIntegrationTest {

	private static final ProjectServiceStub PROJECT_SERVICE = ProjectServiceStub.INSTANCE;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.tenant.host", TenantFixtures.MYSQL::getHost);
		registry.add("spring.datasource.tenant.port", () -> TenantFixtures.MYSQL.getMappedPort(3306));
		registry.add("spring.datasource.tenant.username", () -> "root");
		registry.add("spring.datasource.tenant.password", () -> TenantFixtures.ROOT_PASSWORD);
		registry.add("spring.datasource.tenant.jdbc-parameters", () -> TenantFixtures.PARAMS);
		registry.add("sprintmodus.jwt.secret", () -> TenantFixtures.SECRET);
		// Eureka is off in tests; resolve project-service to the stub through the load balancer instead
		registry.add("spring.cloud.discovery.client.simple.instances.project-service[0].uri", () -> "http://localhost:" + PROJECT_SERVICE.port());
	}

	@Autowired
	MockMvc mvc;

	@AfterEach
	void projectServiceIsBackUp() {
		PROJECT_SERVICE.down(false);
	}

	private record Caller(Tenant tenant, User user) {

		String token() {
			return TenantFixtures.token(tenant, user);
		}

	}

	private static Caller owner(Tenant tenant) {
		return new Caller(tenant, tenant.owner());
	}

	private static Caller admin(Tenant tenant) {
		return new Caller(tenant, tenant.admin());
	}

	private static Caller member(Tenant tenant) {
		return new Caller(tenant, tenant.member());
	}

	private ResultActions send(MockHttpServletRequestBuilder request, Caller caller, String body) throws Exception {
		request.header("Authorization", "Bearer " + caller.token());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private String read(ResultActions result, String path) throws Exception {
		Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
		return String.valueOf(value);
	}

	private ResultActions create(Caller caller, UUID project, String type, String title, UUID parent, Integer points) throws Exception {
		String parentJson = parent == null ? "" : ",\"parentCode\":\"%s\"".formatted(parent);
		String pointsJson = points == null ? "" : ",\"effortPoints\":%d".formatted(points);
		return send(post("/api/work-items"), caller,
				"{\"projectCode\":\"%s\",\"type\":\"%s\",\"title\":\"%s\"%s%s}".formatted(project, type, title, parentJson, pointsJson));
	}

	private UUID newItem(Caller caller, UUID project, String type, String title, UUID parent, Integer points) throws Exception {
		return UUID.fromString(read(create(caller, project, type, title, parent, points).andExpect(status().isCreated()), "$.workItemCode"));
	}

	private UUID newItem(Caller caller, UUID project, String type, String title) throws Exception {
		return newItem(caller, project, type, title, null, null);
	}

	private ResultActions changeStatus(Caller caller, UUID item, String status) throws Exception {
		return send(post("/api/work-items/" + item + "/status"), caller, "{\"status\":\"%s\"}".formatted(status));
	}

	private static List<String> audit(Tenant tenant, UUID item) {
		return tenant.strings("""
				SELECT CONCAT(a.ChangeType, '|', COALESCE(a.FieldChanged, ''), '|', COALESCE(a.OldValue, ''), '|', COALESCE(a.NewValue, ''), '|', u.Email)
				FROM WorkItemAudit a JOIN WorkItem w ON w.WorkItemId = a.WorkItemId JOIN `User` u ON u.UserId = a.ChangedBy
				WHERE w.WorkItemCode = UUID_TO_BIN(?) ORDER BY a.AuditId""", item.toString());
	}

	// ---------------------------------------------------------------- creating

	@Test
	void everyTypeCanBeCreatedWithItsDisplayKeySequentialNumberAndInitialStatus() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");

		ResultActions epic = create(owner, project, "EPIC", "Checkout", null, null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.workItemNumber").value(1000)).andExpect(jsonPath("$.displayKey").value("WAR-1000"))
				.andExpect(jsonPath("$.type").value("EPIC")).andExpect(jsonPath("$.status.code").value("BACKLOG"))
				.andExpect(jsonPath("$.projectCode").value(project.toString())).andExpect(jsonPath("$.warnings.length()").value(0))
				.andExpect(jsonPath("$.createdBy.userCode").value(tenant.owner().code().toString()))
				.andExpect(jsonPath("$.priority").value("MEDIUM")).andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.workItemId").doesNotExist());
		UUID epicCode = UUID.fromString(read(epic, "$.workItemCode"));
		assertThat(epicCode.version()).as("an opaque v4 UUID").isEqualTo(4);

		UUID feature = newItem(owner, project, "FEATURE", "Payments", epicCode, null);
		UUID pbi = newItem(owner, project, "PBI", "Pay by card", feature, 5);
		UUID bug = newItem(owner, project, "BUG", "Card rejected", feature, 2);
		UUID task = newItem(owner, project, "TASK", "Call the gateway", pbi, null);

		send(get("/api/work-items/" + task), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.workItemNumber").value(1004))
				.andExpect(jsonPath("$.status.code").value("NEW")).andExpect(jsonPath("$.parentCode").value(pbi.toString()))
				.andExpect(jsonPath("$.allowedStatuses[0].code").value("IN_PROGRESS"));
		send(get("/api/work-items/" + pbi), owner, null).andExpect(jsonPath("$.effortPoints").value(5))
				.andExpect(jsonPath("$.status.code").value("NEW")).andExpect(jsonPath("$.children[0].workItemCode").value(task.toString()))
				.andExpect(jsonPath("$.allowedStatuses[0].code").value("APPROVED"));
		send(get("/api/work-items/" + bug), owner, null).andExpect(jsonPath("$.type").value("BUG"));

		assertThat(audit(tenant, epicCode)).containsExactly("CREATED|||Checkout|" + tenant.owner().email());
	}

	@Test
	void rejectsInvalidData() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");

		create(owner, project, "PBI", "  ", null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
		create(owner, project, "PBI", "Too many points", null, 5000).andExpect(status().isBadRequest());
		create(owner, UUID.randomUUID(), "PBI", "No such project", null, null).andExpect(status().isNotFound());
		send(post("/api/work-items"), owner, "{\"projectCode\":\"%s\",\"title\":\"No type\"}".formatted(project)).andExpect(status().isBadRequest());
		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItem")).isEqualTo("0");
		assertThat(tenant.string("SELECT NextNumber FROM WorkItemSequence")).as("failed creations use no number").isEqualTo("1000");
	}

	@Test
	void numbersAreGaplessAndUniqueEvenWhenItemsAreCreatedConcurrently() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID other = tenant.newProject("OTH");
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 24; i++) {
				UUID target = i % 4 == 0 ? other : project;
				String title = "Concurrent " + i;
				results.add(executor.submit(() -> {
					start.await();
					return create(owner, target, "PBI", title, null, null).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(201);
			}
		}

		assertThat(tenant.strings("SELECT WorkItemNumber FROM WorkItem w JOIN Project p ON p.ProjectId = w.ProjectId WHERE p.`Key` = 'WAR' ORDER BY 1"))
				.containsExactlyElementsOf(java.util.stream.IntStream.range(1000, 1018).mapToObj(String::valueOf).toList());
		assertThat(tenant.strings("SELECT WorkItemNumber FROM WorkItem w JOIN Project p ON p.ProjectId = w.ProjectId WHERE p.`Key` = 'OTH' ORDER BY 1"))
				.containsExactlyElementsOf(java.util.stream.IntStream.range(1000, 1006).mapToObj(String::valueOf).toList());
		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItemAudit WHERE ChangeType = 'CREATED'")).isEqualTo("24");
	}

	// ---------------------------------------------------------------- hierarchy

	@Test
	void aNonStandardParentSucceedsAndOnlyWarns() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID epic = newItem(owner, project, "EPIC", "Big");

		ResultActions task = create(owner, project, "TASK", "Directly under an epic", epic, null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.warnings[0].code").value("NON_STANDARD_HIERARCHY")).andExpect(jsonPath("$.parentCode").value(epic.toString()));
		UUID taskCode = UUID.fromString(read(task, "$.workItemCode"));
		assertThat(read(task, "$.warnings[0].message")).contains("EPIC").contains("TASK");

		// reading it back does not repeat the warning: it is about the change, not the item
		send(get("/api/work-items/" + taskCode), owner, null).andExpect(jsonPath("$.warnings.length()").value(0));

		UUID feature = newItem(owner, project, "FEATURE", "Feature under epic");
		send(put("/api/work-items/" + feature + "/parent"), owner, "{\"parentCode\":\"%s\"}".formatted(epic)).andExpect(status().isOk())
				.andExpect(jsonPath("$.warnings.length()").value(0));
		UUID epic2 = newItem(owner, project, "EPIC", "Epic under feature");
		send(put("/api/work-items/" + epic2 + "/parent"), owner, "{\"parentCode\":\"%s\"}".formatted(feature)).andExpect(status().isOk())
				.andExpect(jsonPath("$.warnings[0].code").value("NON_STANDARD_HIERARCHY")).andExpect(jsonPath("$.parentCode").value(feature.toString()));

		assertThat(audit(tenant, epic2)).last().asString().startsWith("PARENT_CHANGED|");
		send(delete("/api/work-items/" + epic2 + "/parent"), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.parentCode").doesNotExist());
	}

	@Test
	void parentsMustExistBelongToTheSameProjectAndNeverFormACycle() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID otherProject = tenant.newProject("OTH");
		UUID epic = newItem(owner, project, "EPIC", "Epic");
		UUID feature = newItem(owner, project, "FEATURE", "Feature", epic, null);
		UUID pbi = newItem(owner, project, "PBI", "Pbi", feature, null);
		UUID foreign = newItem(owner, otherProject, "EPIC", "Elsewhere");

		create(owner, project, "PBI", "Orphan", UUID.randomUUID(), null).andExpect(status().isBadRequest());
		create(owner, project, "PBI", "Cross project", foreign, null).andExpect(status().isBadRequest());
		send(put("/api/work-items/" + epic + "/parent"), owner, "{\"parentCode\":\"%s\"}".formatted(pbi)).andExpect(status().isBadRequest());
		send(put("/api/work-items/" + epic + "/parent"), owner, "{\"parentCode\":\"%s\"}".formatted(epic)).andExpect(status().isBadRequest());
		send(put("/api/work-items/" + pbi + "/parent"), owner, "{}").andExpect(status().isBadRequest());
		send(get("/api/work-items/" + epic), owner, null).andExpect(jsonPath("$.parentCode").doesNotExist());
	}

	// ---------------------------------------------------------------- status

	@Test
	void statusChangesFollowTheWorkflowAndAreAudited() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Story");

		send(get("/api/work-items/" + pbi), member, null).andExpect(jsonPath("$.status.isInitial").value(true))
				.andExpect(jsonPath("$.status.isTerminal").value(false)).andExpect(jsonPath("$.status.color").doesNotExist())
				.andExpect(jsonPath("$.allowedStatuses[0].color").doesNotExist());
		changeStatus(member, pbi, "APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.status.code").value("APPROVED"))
				.andExpect(jsonPath("$.status.isInitial").value(false)).andExpect(jsonPath("$.allowedStatuses[*].code").value(org.hamcrest.Matchers.containsInAnyOrder("COMMITTED", "NEW")));
		changeStatus(member, pbi, "DONE").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
				.andExpect(jsonPath("$.message").value("Cannot move from Aprobado to Hecho."));
		changeStatus(member, pbi, "NO_SUCH_STATUS").andExpect(status().isBadRequest());
		changeStatus(member, pbi, "NEW").andExpect(status().isOk());
		changeStatus(member, UUID.randomUUID(), "APPROVED").andExpect(status().isNotFound());

		assertThat(audit(tenant, pbi)).containsExactly("CREATED|||Story|" + tenant.owner().email(),
				"STATE_CHANGED|Status|NEW|APPROVED|" + tenant.member().email(), "STATE_CHANGED|Status|APPROVED|NEW|" + tenant.member().email());
	}

	@Test
	void tasksAndEpicsHaveTheirOwnWorkflows() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID task = newItem(owner, project, "TASK", "Task");
		UUID epic = newItem(owner, project, "EPIC", "Epic");

		changeStatus(owner, task, "IN_PROGRESS").andExpect(status().isOk());
		changeStatus(owner, task, "ON_HOLD").andExpect(status().isOk());
		changeStatus(owner, task, "DONE").andExpect(status().isOk()).andExpect(jsonPath("$.status.isTerminal").value(true));
		changeStatus(owner, epic, "IN_PLANNING").andExpect(status().isOk());
		changeStatus(owner, epic, "IN_PROGRESS").andExpect(status().isBadRequest());
		send(get("/api/status-workflows/TASK"), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.statuses.length()").value(4))
				.andExpect(jsonPath("$.statuses[0].code").value("NEW")).andExpect(jsonPath("$.transitions.length()").value(3));
		send(get("/api/status-workflows/PBI"), owner, null).andExpect(jsonPath("$.statuses.length()").value(17))
				.andExpect(jsonPath("$.statuses[0].color").doesNotExist());
	}

	@Test
	void twoConcurrentChangesFromTheSameStatusLeaveExactlyOneWinner() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Contended");
		CountDownLatch start = new CountDownLatch(1);

		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return changeStatus(owner, pbi, "APPROVED").andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
		}

		assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
		assertThat(statuses).filteredOn(code -> code != 200).allMatch(code -> code == 409);
		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItemAudit WHERE ChangeType = 'STATE_CHANGED'")).as("one audit row per real change").isEqualTo("1");
	}

	@Test
	void aFailedAuditRowUndoesTheStatusChangeThatItBelongsTo() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Atomic");
		// the audit insert of a status change is made to fail, as a full disk or a lock timeout would
		tenant.execute("""
				CREATE TRIGGER fail_state_audit BEFORE INSERT ON WorkItemAudit FOR EACH ROW
				BEGIN
				  IF NEW.ChangeType = 'STATE_CHANGED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit failed'; END IF;
				END""");

		changeStatus(owner, pbi, "APPROVED").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		send(get("/api/work-items/" + pbi), owner, null).andExpect(jsonPath("$.status.code").value("NEW"));
		assertThat(tenant.strings("SELECT ChangeType FROM WorkItemAudit")).containsExactly("CREATED");

		tenant.execute("DROP TRIGGER fail_state_audit");
		changeStatus(owner, pbi, "APPROVED").andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- editing, assigning, deleting

	@Test
	void updatesFieldsAndAuditsEachKindOfChange() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Old title", null, 3);

		send(put("/api/work-items/" + pbi), member,
				"{\"title\":\"New title\",\"priority\":\"HIGH\",\"effortPoints\":8,\"estimatedHours\":12.5,\"description\":\"Details\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("New title")).andExpect(jsonPath("$.priority").value("HIGH"))
				.andExpect(jsonPath("$.effortPoints").value(8)).andExpect(jsonPath("$.estimatedHours").value(12.5))
				.andExpect(jsonPath("$.description").value("Details")).andExpect(jsonPath("$.updatedBy.userCode").value(tenant.member().code().toString()));
		send(put("/api/work-items/" + pbi), member, "{\"title\":\"\"}").andExpect(status().isBadRequest());
		send(put("/api/work-items/" + pbi), member, "{\"effortPoints\":-1}").andExpect(status().isBadRequest());
		send(put("/api/work-items/" + pbi), member, "{\"description\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());

		assertThat(audit(tenant, pbi)).contains("FIELD_CHANGED|Title|Old title|New title|" + tenant.member().email(),
				"FIELD_CHANGED|Priority|MEDIUM|HIGH|" + tenant.member().email(), "EFFORT_CHANGED|EffortPoints|3|8|" + tenant.member().email())
				.anyMatch(line -> line.startsWith("DESCRIPTION_EDITED|"));
	}

	@Test
	void assignsUsersInRolesAndUnassignsThem() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Story");
		String body = "{\"userCode\":\"%s\",\"role\":\"DEV\"}".formatted(tenant.member().code());

		ResultActions assigned = send(post("/api/work-items/" + pbi + "/assignments"), owner, body).andExpect(status().isCreated())
				.andExpect(jsonPath("$.fullName").value("Mia Member")).andExpect(jsonPath("$.role").value("DEV"));
		String assignment = read(assigned, "$.assignmentCode");
		send(post("/api/work-items/" + pbi + "/assignments"), owner, body).andExpect(status().isConflict());
		send(post("/api/work-items/" + pbi + "/assignments"), owner, "{\"userCode\":\"%s\",\"role\":\"QA\"}".formatted(UUID.randomUUID()))
				.andExpect(status().isNotFound());
		send(get("/api/work-items/" + pbi), owner, null).andExpect(jsonPath("$.assignees.length()").value(1))
				.andExpect(jsonPath("$.assignees[0].userCode").value(tenant.member().code().toString()));

		send(delete("/api/work-items/" + pbi + "/assignments/" + assignment), owner, null).andExpect(status().isNoContent());
		send(delete("/api/work-items/" + pbi + "/assignments/" + assignment), owner, null).andExpect(status().isNotFound());
		send(get("/api/work-items/" + pbi), owner, null).andExpect(jsonPath("$.assignees.length()").value(0));
		// assigning the same user and role again reactivates the row
		send(post("/api/work-items/" + pbi + "/assignments"), owner, body).andExpect(status().isCreated());

		assertThat(audit(tenant, pbi)).anyMatch(line -> line.startsWith("ASSIGNED|")).anyMatch(line -> line.startsWith("UNASSIGNED|"));
		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItemAssignment")).isEqualTo("1");
	}

	@Test
	void deletingIsASoftDeleteLimitedToTheCreatorOrAnAdminAndBlockedByChildren() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID mine = newItem(member, project, "PBI", "Mine");
		UUID notMine = newItem(owner, project, "PBI", "Not mine");
		UUID parent = newItem(member, project, "PBI", "Parent");
		UUID child = newItem(member, project, "TASK", "Child", parent, null);

		send(delete("/api/work-items/" + notMine), member, null).andExpect(status().isForbidden());
		send(delete("/api/work-items/" + parent), member, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_HAS_CHILDREN"));
		send(delete("/api/work-items/" + mine), member, null).andExpect(status().isNoContent());
		send(get("/api/work-items/" + mine), member, null).andExpect(status().isNotFound());
		send(delete("/api/work-items/" + mine), member, null).andExpect(status().isNotFound());
		send(delete("/api/work-items/" + child), member, null).andExpect(status().isNoContent());
		send(delete("/api/work-items/" + parent), admin(tenant), null).andExpect(status().isNoContent());
		send(delete("/api/work-items/" + notMine), admin(tenant), null).andExpect(status().isNoContent());

		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItem")).as("rows stay").isEqualTo("4");
		assertThat(tenant.string("SELECT COUNT(*) FROM WorkItem WHERE IsActive = TRUE")).isEqualTo("0");
		assertThat(audit(tenant, mine)).last().asString().startsWith("DELETED|");
		send(get("/api/work-items?projectCode=" + project), owner, null).andExpect(jsonPath("$.total").value(0));
	}

	// ---------------------------------------------------------------- sprints

	@Test
	void movingBetweenSprintsUpdatesTheVelocityOfBothEnds() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID first = tenant.newSprint(project, "Sprint 1", "ACTIVE");
		UUID second = tenant.newSprint(project, "Sprint 2", "ACTIVE");
		PROJECT_SERVICE.register(first, project, "ACTIVE");
		PROJECT_SERVICE.register(second, project, "ACTIVE");
		// a two-step PBI workflow makes "completed" easy to reach
		send(post("/api/admin/status-workflows"), owner, """
				{"itemType":"PBI","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"DONE"}]}""").andExpect(status().isOk());
		UUID done = newItem(owner, project, "PBI", "Finished", null, 5);
		UUID open = newItem(owner, project, "PBI", "Open", null, 8);
		changeStatus(owner, done, "DONE").andExpect(status().isOk());

		send(put("/api/work-items/" + done + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(first)).andExpect(status().isOk())
				.andExpect(jsonPath("$.sprintCode").value(first.toString())).andExpect(jsonPath("$.warnings.length()").value(0));
		send(put("/api/work-items/" + open + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(first)).andExpect(status().isOk());
		assertThat(PROJECT_SERVICE.velocityOf(first)).as("only completed effort counts").isEqualTo(5);

		send(put("/api/work-items/" + done + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(second)).andExpect(status().isOk());
		assertThat(PROJECT_SERVICE.velocityOf(first)).as("the sprint it left").isEqualTo(0);
		assertThat(PROJECT_SERVICE.velocityOf(second)).as("the sprint it joined").isEqualTo(5);

		send(delete("/api/work-items/" + done + "/sprint"), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.sprintCode").doesNotExist());
		assertThat(PROJECT_SERVICE.velocityOf(second)).isEqualTo(0);
		send(get("/api/work-items?projectCode=" + project + "&backlog=true"), owner, null).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].workItemCode").value(done.toString()));
		send(get("/api/work-items?sprintCode=" + first), owner, null).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].workItemCode").value(open.toString()));

		assertThat(audit(tenant, done)).filteredOn(line -> line.startsWith("SPRINT_CHANGED|")).hasSize(3);
		assertThat(PROJECT_SERVICE.calls()).filteredOn(call -> call.method().equals("PUT")
				&& (call.path().contains(first.toString()) || call.path().contains(second.toString()))).isNotEmpty().extracting(ProjectServiceStub.Call::authorization)
				.allMatch(header -> header != null && header.startsWith("Bearer ") && new String(java.util.Base64.getUrlDecoder()
						.decode(header.substring(7).split("\\.")[1])).contains(tenant.owner().code().toString()));
	}

	@Test
	void aSprintOfAnotherProjectOrAClosedOneRefusesTheItem() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID other = tenant.newProject("OTH");
		UUID closed = tenant.newSprint(project, "Old", "CLOSED");
		UUID foreign = tenant.newSprint(other, "Elsewhere", "ACTIVE");
		PROJECT_SERVICE.register(closed, project, "CLOSED");
		PROJECT_SERVICE.register(foreign, other, "ACTIVE");
		UUID pbi = newItem(owner, project, "PBI", "Story");

		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(closed)).andExpect(status().isConflict());
		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(foreign)).andExpect(status().isConflict());
		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(UUID.randomUUID())).andExpect(status().isNotFound());
		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{}").andExpect(status().isBadRequest());
		send(get("/api/work-items/" + pbi), owner, null).andExpect(jsonPath("$.sprintCode").doesNotExist());
		assertThat(audit(tenant, pbi)).containsExactly("CREATED|||Story|" + tenant.owner().email());
	}

	@Test
	void whenProjectServiceIsDownTheMoveIsRefusedAndAStaleVelocityOnlyWarns() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID sprint = tenant.newSprint(project, "Sprint", "ACTIVE");
		PROJECT_SERVICE.register(sprint, project, "ACTIVE");
		UUID pbi = newItem(owner, project, "PBI", "Story", null, 3);

		PROJECT_SERVICE.down(true);
		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(sprint)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROJECT_SERVICE_UNAVAILABLE"));
		send(get("/api/work-items/" + pbi), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.sprintCode").doesNotExist());

		PROJECT_SERVICE.down(false);
		send(put("/api/work-items/" + pbi + "/sprint"), owner, "{\"sprintCode\":\"%s\"}".formatted(sprint)).andExpect(status().isOk());
	}

	@Test
	void onlyOwnersAndAdminsMoveWorkItemsBetweenSprints() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller member = member(tenant);
		Caller admin = admin(tenant);
		UUID project = tenant.newProject("WAR");
		UUID sprint = tenant.newSprint(project, "Sprint", "ACTIVE");
		PROJECT_SERVICE.register(sprint, project, "ACTIVE");
		UUID pbi = newItem(member, project, "PBI", "Story");

		send(put("/api/work-items/" + pbi + "/sprint"), member, "{\"sprintCode\":\"%s\"}".formatted(sprint)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		send(get("/api/work-items/" + pbi), member, null).andExpect(jsonPath("$.sprintCode").doesNotExist());

		send(put("/api/work-items/" + pbi + "/sprint"), admin, "{\"sprintCode\":\"%s\"}".formatted(sprint)).andExpect(status().isOk());
		send(delete("/api/work-items/" + pbi + "/sprint"), member, null).andExpect(status().isForbidden());
		send(get("/api/work-items/" + pbi), member, null).andExpect(jsonPath("$.sprintCode").value(sprint.toString()));
	}

	// ---------------------------------------------------------------- burndown

	private ResultActions setHours(Caller caller, UUID item, String hours) throws Exception {
		return send(put("/api/work-items/" + item), caller, "{\"estimatedHours\":%s,\"remainingHours\":%s}".formatted(hours, hours));
	}

	@Test
	void everyChangeToTheHoursOfAnActiveSprintStoresTodaysSnapshotAndTheLastOneWins() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller admin = admin(tenant);
		UUID project = tenant.newProject("WAR");
		UUID active = tenant.newSprint(project, "Active", "ACTIVE");
		UUID planned = tenant.newSprint(project, "Planned", "PLANNED");
		UUID closed = tenant.newSprint(project, "Closed", "CLOSED");
		for (UUID sprint : List.of(active, planned, closed)) {
			PROJECT_SERVICE.register(sprint, project, sprint.equals(closed) ? "CLOSED" : sprint.equals(planned) ? "PLANNED" : "ACTIVE");
		}
		send(post("/api/admin/status-workflows"), admin, """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"DONE","allowedBackward":true}]}""").andExpect(status().isOk());
		UUID first = newItem(admin, project, "TASK", "First");
		UUID second = newItem(admin, project, "TASK", "Second");
		setHours(admin, first, "6").andExpect(status().isOk());
		setHours(admin, second, "4").andExpect(status().isOk());

		String snapshots = "SELECT CONCAT(b.RemainingHours, '/', b.SnapshotDate = UTC_DATE()) FROM BurndownData b JOIN Sprint s ON s.SprintId = b.SprintId "
				+ "WHERE s.SprintCode = UUID_TO_BIN(?)";
		assertThat(tenant.strings(snapshots, active.toString())).as("nothing is in the sprint yet, so nothing was recorded").isEmpty();

		send(put("/api/work-items/" + first + "/sprint"), admin, "{\"sprintCode\":\"%s\"}".formatted(active)).andExpect(status().isOk());
		send(put("/api/work-items/" + second + "/sprint"), admin, "{\"sprintCode\":\"%s\"}".formatted(active)).andExpect(status().isOk());
		assertThat(tenant.strings(snapshots, active.toString())).as("one row for today holding the last value").containsExactly("10.00/1");

		changeStatus(admin, first, "DONE").andExpect(status().isOk());
		assertThat(tenant.strings(snapshots, active.toString())).as("a finished task has nothing left").containsExactly("4.00/1");

		setHours(admin, second, "1.5").andExpect(status().isOk());
		assertThat(tenant.strings(snapshots, active.toString())).containsExactly("1.50/1");

		changeStatus(admin, first, "NEW").andExpect(status().isOk());
		assertThat(tenant.strings(snapshots, active.toString())).as("reopened: its hours count again").containsExactly("7.50/1");

		send(delete("/api/work-items/" + second), admin, null).andExpect(status().isNoContent());
		assertThat(tenant.strings(snapshots, active.toString())).containsExactly("6.00/1");

		send(put("/api/work-items/" + first + "/sprint"), admin, "{\"sprintCode\":\"%s\"}".formatted(planned)).andExpect(status().isOk());
		assertThat(tenant.strings(snapshots, active.toString())).as("it left the sprint").containsExactly("0.00/1");
		assertThat(tenant.strings(snapshots, planned.toString())).as("a planned sprint has no burndown yet").isEmpty();
		assertThat(tenant.strings(snapshots, closed.toString())).as("a closed sprint's data is locked").isEmpty();
	}

	// ---------------------------------------------------------------- board order

	private void setPriority(Caller caller, UUID item, String priority) throws Exception {
		send(put("/api/work-items/" + item), caller, "{\"priority\":\"%s\"}".formatted(priority)).andExpect(status().isOk());
	}

	private List<String> boardColumn(Caller caller, UUID project, String status) throws Exception {
		ResultActions listed = send(get("/api/work-items?projectCode=" + project + "&type=PBI&status=" + status + "&sort=board"), caller, null)
				.andExpect(status().isOk());
		return JsonPath.<List<String>>read(listed.andReturn().getResponse().getContentAsString(), "$.items[*].title");
	}

	@Test
	void aBoardColumnIsOrderedByPriorityThenByTheRankAnOwnerOrAdminSets() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller admin = admin(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID low = newItem(owner, project, "PBI", "Low");
		UUID mediumA = newItem(owner, project, "PBI", "Medium A");
		UUID mediumB = newItem(owner, project, "PBI", "Medium B");
		UUID mediumC = newItem(owner, project, "PBI", "Medium C");
		UUID critical = newItem(owner, project, "PBI", "Critical");
		setPriority(owner, low, "LOW");
		setPriority(owner, critical, "CRITICAL");

		assertThat(boardColumn(member, project, "NEW")).as("never ranked: by number inside a priority")
				.containsExactly("Critical", "Medium A", "Medium B", "Medium C", "Low");
		send(get("/api/work-items?projectCode=" + project + "&type=PBI"), member, null).andExpect(jsonPath("$.items[0].title").value("Critical"))
				.andExpect(jsonPath("$.items[1].title").value("Medium C")); // the default order is still newest number first

		send(put("/api/work-items/" + mediumC + "/rank"), admin, "{\"beforeCode\":\"%s\"}".formatted(mediumA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.workItemCode").value(mediumC.toString()));
		assertThat(boardColumn(member, project, "NEW")).containsExactly("Critical", "Medium C", "Medium A", "Medium B", "Low");
		assertThat(tenant.strings("SELECT CONCAT(Title, ':', COALESCE(BoardRank, 'null')) FROM WorkItem WHERE Priority = 'MEDIUM' ORDER BY WorkItemNumber"))
				.containsExactly("Medium A:2", "Medium B:3", "Medium C:1");
		assertThat(audit(tenant, mediumC)).contains("FIELD_CHANGED|BoardRank||1|" + tenant.admin().email());

		send(put("/api/work-items/" + mediumC + "/rank"), owner, "{}").andExpect(status().isOk());
		assertThat(boardColumn(member, project, "NEW")).as("no target: last in its priority").containsExactly("Critical", "Medium A", "Medium B", "Medium C", "Low");

		send(put("/api/work-items/" + mediumC + "/rank"), member, "{\"beforeCode\":\"%s\"}".formatted(mediumA)).andExpect(status().isForbidden());
		send(put("/api/work-items/" + mediumC + "/rank"), owner, "{\"beforeCode\":\"%s\"}".formatted(critical)).andExpect(status().isBadRequest());
		send(put("/api/work-items/" + mediumC + "/rank"), owner, "{\"beforeCode\":\"%s\"}".formatted(mediumC)).andExpect(status().isBadRequest());
		send(put("/api/work-items/" + UUID.randomUUID() + "/rank"), owner, "{}").andExpect(status().isNotFound());
	}

	@Test
	void aCardThatChangesStatusOrPriorityJoinsTheEndOfItsNewGroup() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID a = newItem(owner, project, "PBI", "A");
		UUID b = newItem(owner, project, "PBI", "B");
		UUID c = newItem(owner, project, "PBI", "C");
		send(put("/api/work-items/" + c + "/rank"), owner, "{\"beforeCode\":\"%s\"}".formatted(a)).andExpect(status().isOk());

		setPriority(owner, c, "HIGH");
		assertThat(tenant.string("SELECT BoardRank IS NULL FROM WorkItem WHERE Title = 'C'")).as("another priority: another group").isEqualTo("1");
		assertThat(tenant.string("SELECT BoardRank FROM WorkItem WHERE Title = 'A'")).as("the group it left keeps its ranks").isEqualTo("2");

		send(put("/api/work-items/" + a + "/rank"), owner, "{\"beforeCode\":\"%s\"}".formatted(b)).andExpect(status().isOk());
		changeStatus(owner, a, "APPROVED").andExpect(status().isOk());
		assertThat(tenant.string("SELECT BoardRank IS NULL FROM WorkItem WHERE Title = 'A'")).as("another status: another group").isEqualTo("1");
		setPriority(owner, b, "MEDIUM");
		assertThat(tenant.string("SELECT BoardRank FROM WorkItem WHERE Title = 'B'")).as("the same priority again changes nothing").isEqualTo("2");
	}

	@Test
	void theListCarriesTheAssigneesAndTheChildCountOfEveryRow() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Story");
		UUID lonely = newItem(owner, project, "PBI", "Lonely");
		newItem(owner, project, "TASK", "One", pbi, null);
		newItem(owner, project, "TASK", "Two", pbi, null);
		send(post("/api/work-items/" + pbi + "/assignments"), owner,
				"{\"userCode\":\"%s\",\"role\":\"DEV\"}".formatted(tenant.member().code())).andExpect(status().isCreated());

		send(get("/api/work-items?projectCode=" + project + "&type=PBI"), owner, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.workItemCode == '%s')].childCount".formatted(pbi)).value(2))
				.andExpect(jsonPath("$.items[?(@.workItemCode == '%s')].assignees[0].fullName".formatted(pbi)).value("Mia Member"))
				.andExpect(jsonPath("$.items[?(@.workItemCode == '%s')].assignees[0].role".formatted(pbi)).value("DEV"))
				.andExpect(jsonPath("$.items[?(@.workItemCode == '%s')].childCount".formatted(lonely)).value(0))
				.andExpect(jsonPath("$.items[?(@.workItemCode == '%s')].assignees.length()".formatted(lonely)).value(0));
		send(get("/api/work-items?parentCode=" + pbi), owner, null).andExpect(jsonPath("$.total").value(2));
	}

	// ---------------------------------------------------------------- comments

	@Test
	void commentsAreListedOldestFirstAndAudited() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID pbi = newItem(owner, project, "PBI", "Story");

		send(post("/api/work-items/" + pbi + "/comments"), member, "{\"content\":\"First!\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.content").value("First!")).andExpect(jsonPath("$.author.fullName").value("Mia Member"))
				.andExpect(jsonPath("$.workItemCode").value(pbi.toString())).andExpect(jsonPath("$.commentCode").exists());
		send(post("/api/work-items/" + pbi + "/comments"), owner, "{\"content\":\"Second\"}").andExpect(status().isCreated());
		send(post("/api/work-items/" + pbi + "/comments"), owner, "{\"content\":\"   \"}").andExpect(status().isBadRequest());
		send(post("/api/work-items/" + UUID.randomUUID() + "/comments"), owner, "{\"content\":\"Hi\"}").andExpect(status().isNotFound());

		send(get("/api/work-items/" + pbi + "/comments"), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].content").value("First!")).andExpect(jsonPath("$[1].content").value("Second"));
		assertThat(audit(tenant, pbi)).contains("COMMENTED|Comment||First!|" + tenant.member().email(), "COMMENTED|Comment||Second|" + tenant.owner().email());
		send(delete("/api/work-items/" + pbi + "/comments"), owner, null).andExpect(status().is4xxClientError());
	}

	// ---------------------------------------------------------------- listing

	@Test
	void listsWithFiltersAndPagination() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID other = tenant.newProject("OTH");
		UUID epic = newItem(owner, project, "EPIC", "Epic");
		UUID feature = newItem(owner, project, "FEATURE", "Feature", epic, null);
		List<UUID> pbis = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			pbis.add(newItem(owner, project, "PBI", "Story " + i, feature, i));
		}
		newItem(owner, other, "PBI", "Elsewhere");
		changeStatus(owner, pbis.get(0), "APPROVED").andExpect(status().isOk());
		send(put("/api/work-items/" + pbis.get(1)), owner, "{\"priority\":\"CRITICAL\"}").andExpect(status().isOk());
		send(post("/api/work-items/" + pbis.get(2) + "/assignments"), owner, "{\"userCode\":\"%s\",\"role\":\"DEV\"}".formatted(tenant.member().code()))
				.andExpect(status().isCreated());

		send(get("/api/work-items?projectCode=" + project), owner, null).andExpect(jsonPath("$.total").value(7)).andExpect(jsonPath("$.items.length()").value(7));
		send(get("/api/work-items?projectCode=" + project + "&type=PBI"), owner, null).andExpect(jsonPath("$.total").value(5));
		send(get("/api/work-items?projectCode=" + project + "&type=PBI&status=APPROVED"), owner, null).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].workItemCode").value(pbis.get(0).toString()));
		send(get("/api/work-items?projectCode=" + project + "&priority=CRITICAL"), owner, null).andExpect(jsonPath("$.total").value(1));
		send(get("/api/work-items?projectCode=" + project + "&assignee=" + tenant.member().code()), owner, null).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].workItemCode").value(pbis.get(2).toString()));
		send(get("/api/work-items?parentCode=" + feature), owner, null).andExpect(jsonPath("$.total").value(5));
		send(get("/api/work-items"), owner, null).andExpect(jsonPath("$.total").value(8));

		String secondPage = read(send(get("/api/work-items?projectCode=" + project + "&size=3&page=1"), owner, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(7)).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(3))
				.andExpect(jsonPath("$.items.length()").value(3)), "$.items[0].workItemNumber");
		String firstPage = read(send(get("/api/work-items?projectCode=" + project + "&size=3&page=0"), owner, null), "$.items[0].workItemNumber");
		assertThat(firstPage).isNotEqualTo(secondPage);
		send(get("/api/work-items?projectCode=" + project + "&size=3&page=2"), owner, null).andExpect(jsonPath("$.items.length()").value(1));
		send(get("/api/work-items?size=0"), owner, null).andExpect(status().isBadRequest());
		send(get("/api/work-items?size=500"), owner, null).andExpect(status().isBadRequest());
	}

	@Test
	void searchesTitleAndDescriptionWithTheFullTextIndex() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID checkout = newItem(owner, project, "PBI", "Checkout flow");
		newItem(owner, project, "PBI", "Login page");
		UUID mentionsCheckout = UUID.fromString(read(create(owner, project, "PBI", "Refund handling", null, null)
				.andExpect(status().isCreated()), "$.workItemCode"));
		send(put("/api/work-items/" + mentionsCheckout), owner, "{\"description\":\"Refunds happen after checkout completes.\"}")
				.andExpect(status().isOk());

		send(get("/api/work-items?projectCode=" + project + "&q=checkout"), owner, null).andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[*].workItemCode")
						.value(org.hamcrest.Matchers.containsInAnyOrder(checkout.toString(), mentionsCheckout.toString())));
		send(get("/api/work-items?projectCode=" + project + "&q=login"), owner, null).andExpect(jsonPath("$.total").value(1));
		send(get("/api/work-items?projectCode=" + project + "&q=nonexistentterm"), owner, null).andExpect(jsonPath("$.total").value(0));
		send(get("/api/work-items?projectCode=" + project), owner, null).andExpect(jsonPath("$.total").value(3));
	}

	// ---------------------------------------------------------------- workflow administration

	@Test
	void onlyAdminsCanReplaceAWorkflowAndAStatusInUseCannotBeDropped() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID task = newItem(owner, project, "TASK", "Task");
		String workflow = """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"To do"},{"code":"REVIEW","displayName":"Review"},
				 {"code":"DONE","displayName":"Finished","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"REVIEW"},{"from":"REVIEW","to":"DONE","allowedBackward":true}]}""";

		send(post("/api/admin/status-workflows"), member(tenant), workflow).andExpect(status().isForbidden());
		send(post("/api/admin/status-workflows"), admin(tenant), workflow).andExpect(status().isOk()).andExpect(jsonPath("$.statuses.length()").value(3))
				.andExpect(jsonPath("$.statuses[0].displayName").value("To do")).andExpect(jsonPath("$.statuses[1].code").value("REVIEW"));
		send(get("/api/status-workflows/TASK"), member(tenant), null).andExpect(jsonPath("$.statuses[2].displayName").value("Finished"));
		send(get("/api/work-items/" + task), owner, null).andExpect(jsonPath("$.status.displayName").value("To do"))
				.andExpect(jsonPath("$.allowedStatuses[0].code").value("REVIEW"));
		changeStatus(owner, task, "REVIEW").andExpect(status().isOk());
		changeStatus(owner, task, "DONE").andExpect(status().isOk());
		changeStatus(owner, task, "REVIEW").andExpect(status().isOk());

		// the item sits in REVIEW: a workflow without it would strand the item
		send(post("/api/admin/status-workflows"), owner, """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"DONE"}]}""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATUS_IN_USE"));
		send(post("/api/admin/status-workflows"), owner, "{\"itemType\":\"TASK\",\"statuses\":[]}").andExpect(status().isBadRequest());
		send(get("/api/status-workflows/TASK"), owner, null).andExpect(jsonPath("$.statuses.length()").value(3));

		changeStatus(owner, task, "DONE").andExpect(status().isOk());
		changeStatus(owner, task, "NEW").andExpect(status().isConflict());
		send(post("/api/admin/status-workflows"), owner, """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"DONE"}]}""").andExpect(status().isOk()).andExpect(jsonPath("$.statuses.length()").value(2));
	}

	@Test
	void aTransitionCanRequireAnAssignmentRoleAndAnOwnerOrAdminMayOverrideIt() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		send(post("/api/admin/status-workflows"), owner, """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"NEW","to":"DONE","requiredRole":"PO"}]}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.transitions[0].requiredRole").value("PO"));
		UUID task = newItem(owner, project, "TASK", "Ship it");

		changeStatus(member, task, "DONE").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		send(get("/api/work-items/" + task), member, null).andExpect(jsonPath("$.status.code").value("NEW"));

		send(post("/api/work-items/" + task + "/assignments"), owner, "{\"userCode\":\"%s\",\"role\":\"PO\"}".formatted(tenant.member().code()))
				.andExpect(status().isCreated());
		changeStatus(member, task, "DONE").andExpect(status().isOk()).andExpect(jsonPath("$.status.code").value("DONE"));

		send(post("/api/admin/status-workflows"), owner, """
				{"itemType":"TASK","statuses":[{"code":"NEW","displayName":"New"},{"code":"DONE","displayName":"Done","isTerminal":true}],
				 "transitions":[{"from":"DONE","to":"NEW","requiredRole":"PO","allowedBackward":false}]}""").andExpect(status().isOk());
		// the owner is not assigned as PO, but organization owners always override the restriction
		changeStatus(owner, task, "NEW").andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- relationships

	@Test
	void linksTwoItemsAndListsThemFromEitherEnd() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID a = newItem(owner, project, "PBI", "A");
		UUID b = newItem(owner, project, "PBI", "B");

		String linkCode = read(send(post("/api/work-items/" + a + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"BLOCKS\"}".formatted(b))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("BLOCKS"))
				.andExpect(jsonPath("$.item.workItemCode").value(b.toString())), "$.linkCode");

		send(get("/api/work-items/" + a), owner, null).andExpect(jsonPath("$.links[0].type").value("BLOCKS"))
				.andExpect(jsonPath("$.links[0].item.workItemCode").value(b.toString()));
		send(get("/api/work-items/" + b), owner, null).andExpect(jsonPath("$.links[0].type").value("BLOCKS"))
				.andExpect(jsonPath("$.links[0].item.workItemCode").value(a.toString()));
		assertThat(audit(tenant, a)).last().asString().startsWith("LINKED|Link|");

		send(delete("/api/work-items/" + a + "/links/" + linkCode), owner, null).andExpect(status().isNoContent());
		send(get("/api/work-items/" + a), owner, null).andExpect(jsonPath("$.links.length()").value(0));
		send(get("/api/work-items/" + b), owner, null).andExpect(jsonPath("$.links.length()").value(0));
		assertThat(audit(tenant, a)).last().asString().startsWith("UNLINKED|Link|");
	}

	@Test
	void aLinkRejectsASelfLinkADuplicateAndACycleWithinItsType() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID project = tenant.newProject("WAR");
		UUID a = newItem(owner, project, "PBI", "A");
		UUID b = newItem(owner, project, "PBI", "B");
		UUID c = newItem(owner, project, "PBI", "C");

		send(post("/api/work-items/" + a + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"RELATED_TO\"}".formatted(a))
				.andExpect(status().isBadRequest());
		send(post("/api/work-items/" + a + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"RELATED_TO\"}".formatted(UUID.randomUUID()))
				.andExpect(status().isNotFound());

		send(post("/api/work-items/" + a + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"BLOCKS\"}".formatted(b)).andExpect(status().isCreated());
		send(post("/api/work-items/" + a + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"BLOCKS\"}".formatted(b))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_LINKED"));

		send(post("/api/work-items/" + b + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"BLOCKS\"}".formatted(c)).andExpect(status().isCreated());
		send(post("/api/work-items/" + c + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"BLOCKS\"}".formatted(a))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLIC_LINK"));
		// a different link type between the same pair is unaffected by the BLOCKS cycle
		send(post("/api/work-items/" + c + "/links"), owner, "{\"targetCode\":\"%s\",\"type\":\"RELATED_TO\"}".formatted(a))
				.andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- tenants and security

	@Test
	void aTenantNeverSeesAnotherTenantsWorkItems() throws Exception {
		Tenant alpha = TenantFixtures.newTenant();
		Tenant beta = TenantFixtures.newTenant();
		UUID alphaProject = alpha.newProject("ALP");
		UUID betaProject = beta.newProject("BET");
		UUID alphaItem = newItem(owner(alpha), alphaProject, "PBI", "Alpha secret");
		newItem(owner(beta), betaProject, "PBI", "Beta item");

		send(get("/api/work-items/" + alphaItem), owner(beta), null).andExpect(status().isNotFound());
		send(get("/api/work-items"), owner(beta), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].title").value("Beta item"));
		changeStatus(owner(beta), alphaItem, "APPROVED").andExpect(status().isNotFound());
		send(delete("/api/work-items/" + alphaItem), owner(beta), null).andExpect(status().isNotFound());
		create(owner(beta), alphaProject, "PBI", "Cross tenant", null, null).andExpect(status().isNotFound());
		assertThat(alpha.string("SELECT COUNT(*) FROM WorkItem")).isEqualTo("1");
	}

	@Test
	void requestsWithoutAValidTokenAreRejected() throws Exception {
		mvc.perform(get("/api/work-items")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/status-workflows/PBI").header("Authorization", "Bearer nonsense")).andExpect(status().isUnauthorized());
	}

}
