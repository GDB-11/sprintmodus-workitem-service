package com.sprintmodus.workitem_service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sprintmodus.workitem_service.application.event.TenantEvent;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.NotificationCreated;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.UserMentioned;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.WorkItemCommentAdded;
import com.sprintmodus.workitem_service.integration.TenantFixtures.Tenant;
import com.sprintmodus.workitem_service.integration.TenantFixtures.User;

/**
 * Definition of Done of Phase 14 through the real security filter, the real native-query repositories and a real MySQL with
 * the real tenant migrations: a comment with {@code @mentions} notifies exactly the mentioned people (never twice, never the
 * author), the count and the list agree, a notification is only ever the recipient's, and nothing crosses tenants.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false" })
@AutoConfigureMockMvc
class NotificationsApiIntegrationTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.tenant.host", TenantFixtures.MYSQL::getHost);
		registry.add("spring.datasource.tenant.port", () -> TenantFixtures.MYSQL.getMappedPort(3306));
		registry.add("spring.datasource.tenant.username", () -> "root");
		registry.add("spring.datasource.tenant.password", () -> TenantFixtures.ROOT_PASSWORD);
		registry.add("spring.datasource.tenant.jdbc-parameters", () -> TenantFixtures.PARAMS);
		registry.add("sprintmodus.jwt.secret", () -> TenantFixtures.SECRET);
		registry.add("spring.cloud.discovery.client.simple.instances.project-service[0].uri", () -> "http://localhost:" + ProjectServiceStub.INSTANCE.port());
	}

	private static final tools.jackson.databind.json.JsonMapper JSON = new tools.jackson.databind.json.JsonMapper();

	@Autowired
	MockMvc mvc;

	/** What the e-mail channel will be one day: a plain {@code @EventListener} of {@link TenantEvent}. */
	static class RecordingListener {

		final List<TenantEvent> events = new CopyOnWriteArrayList<>();

		@EventListener
		void on(TenantEvent event) {
			events.add(event);
		}

	}

	@TestConfiguration
	static class ListenerConfig {

		@Bean
		RecordingListener mentionListener() {
			return new RecordingListener();
		}

	}

	@Autowired
	RecordingListener listener;

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
		return String.valueOf((Object) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path));
	}

	private UUID newItem(Caller caller, UUID project, String title) throws Exception {
		return UUID.fromString(read(send(post("/api/work-items"), caller,
				"{\"projectCode\":\"%s\",\"type\":\"PBI\",\"title\":\"%s\"}".formatted(project, title)).andExpect(status().isCreated()), "$.workItemCode"));
	}

	private static String token(User user) {
		return "@[" + user.fullName() + "](" + user.code() + ")";
	}

	private ResultActions comment(Caller caller, UUID item, String text) throws Exception {
		return send(post("/api/work-items/" + item + "/comments"), caller, "{\"content\":" + JSON.writeValueAsString(text) + "}");
	}

	private int unread(Caller caller) throws Exception {
		return Integer.parseInt(read(send(get("/api/notifications/unread-count"), caller, null).andExpect(status().isOk()), "$.count"));
	}

	// ---------------------------------------------------------------- mentions

	@Test
	void aCommentNotifiesExactlyTheMentionedPeopleOnceAndNeverItsAuthor() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller admin = admin(tenant);
		Caller member = member(tenant);
		UUID pbi = newItem(owner, tenant.newProject("WAR"), "Story");
		listener.events.clear();

		String created = read(comment(owner, pbi, "Please look, " + token(tenant.member()) + " and " + token(tenant.member()) + ", also " + token(tenant.owner()))
				.andExpect(status().isCreated()), "$.commentCode");

		assertThat(unread(member)).isEqualTo(1);
		assertThat(unread(admin)).as("not mentioned").isZero();
		assertThat(unread(owner)).as("a self-mention notifies nobody").isZero();
		send(get("/api/notifications"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].type").value("MENTIONED")).andExpect(jsonPath("$.items[0].isRead").value(false))
				.andExpect(jsonPath("$.items[0].workItem.workItemCode").value(pbi.toString())).andExpect(jsonPath("$.items[0].workItem.displayKey").value("WAR-1000"))
				.andExpect(jsonPath("$.items[0].workItem.title").value("Story")).andExpect(jsonPath("$.items[0].commentCode").value(created))
				.andExpect(jsonPath("$.items[0].actor.userCode").value(tenant.owner().code().toString())).andExpect(jsonPath("$.items[0].actor.fullName").value("Olivia Owner"))
				.andExpect(jsonPath("$.items[0].createdAt").exists()).andExpect(jsonPath("$.items[0].notificationCode").exists())
				.andExpect(jsonPath("$.items[0].id").doesNotExist()).andExpect(jsonPath("$.items[0].notificationId").doesNotExist())
				.andExpect(jsonPath("$.items[0].content").doesNotExist());
		assertThat(tenant.strings("SELECT COUNT(*) FROM CommentMention")).as("both people, once each").containsExactly("2");
		assertThat(tenant.strings("SELECT COUNT(*) FROM Notification")).containsExactly("1");
		assertThat(listener.events).extracting(TenantEvent::event).anyMatch(event -> event instanceof WorkItemCommentAdded)
				.anySatisfy(event -> assertThat(event).isEqualTo(new UserMentioned(pbi, tenant.owner().code(), UUID.fromString(created), tenant.member().code())))
				.anyMatch(event -> event instanceof NotificationCreated created2 && created2.userCode().equals(tenant.member().code()));
		assertThat(tenant.strings("SELECT NewValue FROM WorkItemAudit WHERE ChangeType = 'COMMENTED'")).singleElement().satisfies(preview -> assertThat(preview)
				.contains("@Mia Member").doesNotContain("](").doesNotContain(tenant.member().code().toString()));
	}

	@Test
	void aMentionOfAnUnknownOrInactiveUserIsABadRequestAndStoresNothing() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID pbi = newItem(owner, tenant.newProject("WAR"), "Story");
		tenant.execute("UPDATE `User` SET IsActive = FALSE WHERE UserCode = UUID_TO_BIN(?)", tenant.admin().code().toString());

		comment(owner, pbi, "Hi " + token(tenant.member()) + " and @[Ghost](" + UUID.randomUUID() + ")").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_MENTION")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("@Ghost")));
		comment(owner, pbi, "Hi " + token(tenant.admin())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_MENTION"));

		assertThat(tenant.strings("SELECT COUNT(*) FROM Comment")).containsExactly("0");
		assertThat(tenant.strings("SELECT COUNT(*) FROM CommentMention")).containsExactly("0");
		assertThat(tenant.strings("SELECT COUNT(*) FROM Notification")).containsExactly("0");
		assertThat(tenant.strings("SELECT COUNT(*) FROM WorkItemAudit WHERE ChangeType = 'COMMENTED'")).containsExactly("0");
	}

	@Test
	void moreThanTwentyDistinctMentionsAreRefusedAndMalformedTokensStayPlainText() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		UUID pbi = newItem(owner, tenant.newProject("WAR"), "Story");
		StringBuilder twentyOne = new StringBuilder();
		for (int i = 0; i < 21; i++) {
			twentyOne.append("@[Ghost ").append(i).append("](").append(UUID.randomUUID()).append(") ");
		}

		comment(owner, pbi, twentyOne.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMMENT"));
		comment(owner, pbi, "Ping @Mia (plain) or @[Mia](not-a-code)").andExpect(status().isCreated());

		assertThat(tenant.strings("SELECT COUNT(*) FROM Notification")).containsExactly("0");
	}

	// ---------------------------------------------------------------- reading and marking

	@Test
	void notificationsAreNewestFirstPagedFilterableAndMarkedReadOneByOneOrAll() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller admin = admin(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID first = newItem(owner, project, "First story");
		UUID second = newItem(owner, project, "Second story");
		comment(owner, first, token(tenant.member()) + " one").andExpect(status().isCreated());
		comment(admin, second, token(tenant.member()) + " two").andExpect(status().isCreated());
		comment(owner, second, token(tenant.member()) + " three").andExpect(status().isCreated());

		send(get("/api/notifications?size=2"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].workItem.title").value("Second story"))
				.andExpect(jsonPath("$.items[0].actor.fullName").value("Olivia Owner")).andExpect(jsonPath("$.items[1].actor.fullName").value("Adam Admin"));
		send(get("/api/notifications?size=2&page=1"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].workItem.title").value("First story"));
		assertThat(unread(member)).isEqualTo(3);

		String newest = read(send(get("/api/notifications?size=1"), member, null), "$.items[0].notificationCode");
		send(post("/api/notifications/" + newest + "/read"), member, null).andExpect(status().isNoContent());
		send(post("/api/notifications/" + newest + "/read"), member, null).andExpect(status().isNoContent());
		assertThat(unread(member)).isEqualTo(2);
		send(get("/api/notifications?unread=true"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[*].notificationCode").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(newest))));
		send(get("/api/notifications"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].isRead").value(true))
				.andExpect(jsonPath("$.items[0].readAt").exists()).andExpect(jsonPath("$.items[1].isRead").value(false));

		send(post("/api/notifications/read-all"), member, null).andExpect(status().isNoContent());
		assertThat(unread(member)).isZero();
		send(get("/api/notifications?unread=true"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
		send(get("/api/notifications"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
		send(get("/api/notifications?size=0"), member, null).andExpect(status().isBadRequest());
		send(get("/api/notifications?size=101"), member, null).andExpect(status().isBadRequest());
		send(get("/api/notifications?page=-1"), member, null).andExpect(status().isBadRequest());
	}

	@Test
	void someoneElsesNotificationIsANotFoundAndCannotBeMarkedOrListed() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller admin = admin(tenant);
		Caller member = member(tenant);
		UUID pbi = newItem(owner, tenant.newProject("WAR"), "Story");
		comment(owner, pbi, token(tenant.member())).andExpect(status().isCreated());
		String code = read(send(get("/api/notifications"), member, null), "$.items[0].notificationCode");

		send(post("/api/notifications/" + code + "/read"), admin, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		send(post("/api/notifications/" + UUID.randomUUID() + "/read"), member, null).andExpect(status().isNotFound());
		send(post("/api/notifications/read-all"), admin, null).andExpect(status().isNoContent());
		send(get("/api/notifications"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

		assertThat(unread(member)).as("neither the admin's mark nor the admin's read-all touched it").isEqualTo(1);
		mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
	}

	@Test
	void aNotificationAboutADeletedWorkItemDisappearsFromTheCountAndTheList() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Caller owner = owner(tenant);
		Caller member = member(tenant);
		UUID project = tenant.newProject("WAR");
		UUID doomed = newItem(owner, project, "Doomed");
		UUID kept = newItem(owner, project, "Kept");
		comment(owner, doomed, token(tenant.member())).andExpect(status().isCreated());
		comment(owner, kept, token(tenant.member())).andExpect(status().isCreated());
		assertThat(unread(member)).isEqualTo(2);

		send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/work-items/" + doomed), owner, null).andExpect(status().isNoContent());

		assertThat(unread(member)).isEqualTo(1);
		send(get("/api/notifications"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].workItem.title").value("Kept"));
	}

	@Test
	void everyTenantSeesOnlyItsOwnNotificationsAndCannotMentionAnotherTenantsUsers() throws Exception {
		Tenant one = TenantFixtures.newTenant();
		Tenant two = TenantFixtures.newTenant();
		UUID pbi = newItem(owner(one), one.newProject("WAR"), "Story");
		comment(owner(one), pbi, token(one.member())).andExpect(status().isCreated());

		assertThat(unread(member(one))).isEqualTo(1);
		assertThat(unread(member(two))).isZero();
		// a mention of a user of another tenant is unknown here, never delivered across
		UUID other = newItem(owner(two), two.newProject("WAR"), "Story");
		comment(owner(two), other, token(one.member())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_MENTION"));
		assertThat(unread(member(one))).isEqualTo(1);
	}

	// ---------------------------------------------------------------- the user search

	@Test
	void usersAreFoundByTheStartOfAWordOfTheNameWithoutEverExposingAnEmailOrAnotherTenant() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Tenant foreign = TenantFixtures.newTenant();
		foreign.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role) VALUES ('x@y.test', 'hash', 'Mia Foreign', 'MEMBER')");
		tenant.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role) VALUES ('50@y.test', 'hash', '50% Discount', 'MEMBER')");
		tenant.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role, IsActive) VALUES ('gone@y.test', 'hash', 'Mia Gone', 'MEMBER', FALSE)");
		tenant.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role) VALUES ('jose@y.test', 'hash', 'José Pérez', 'MEMBER')");
		Caller member = member(tenant);

		send(get("/api/users?q=mi"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].fullName").value("Mia Member")).andExpect(jsonPath("$[0].userCode").value(tenant.member().code().toString()))
				.andExpect(jsonPath("$[0].email").doesNotExist()).andExpect(jsonPath("$[0].id").doesNotExist());
		send(get("/api/users?q=MEMB"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		send(get("/api/users?q=jose"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].fullName").value("José Pérez"));
		send(get("/api/users").param("q", "%"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		send(get("/api/users").param("q", "50%"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].fullName").value("50% Discount"));
		send(get("/api/users").param("q", "_ia"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		send(get("/api/users?q=foreign"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		send(get("/api/users?q=gone"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		send(get("/api/users"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[0].fullName").value("50% Discount"));
		send(get("/api/users?limit=2"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
		send(get("/api/users?limit=0"), member, null).andExpect(status().isBadRequest());
		send(get("/api/users?limit=51"), member, null).andExpect(status().isBadRequest());
		send(get("/api/users?q=" + "x".repeat(101)), member, null).andExpect(status().isBadRequest());
	}

	@Test
	void theActiveUserCountIncludesOnlyActiveUsersOfTheCallersTenant() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Tenant foreign = TenantFixtures.newTenant();
		foreign.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role) VALUES ('x@y.test', 'hash', 'Mia Foreign', 'MEMBER')");
		tenant.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role) VALUES ('extra@y.test', 'hash', 'Extra Active', 'MEMBER')");
		tenant.execute("INSERT INTO `User` (Email, PasswordHash, FullName, Role, IsActive) VALUES ('gone@y.test', 'hash', 'Mia Gone', 'MEMBER', FALSE)");
		Caller member = member(tenant);

		send(get("/api/users/count"), member, null).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(4));
		mvc.perform(get("/api/users/count")).andExpect(status().isUnauthorized());
	}

}
