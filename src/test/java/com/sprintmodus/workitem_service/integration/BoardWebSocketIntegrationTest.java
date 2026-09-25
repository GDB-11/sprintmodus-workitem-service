package com.sprintmodus.workitem_service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;
import com.sprintmodus.workitem_service.integration.TenantFixtures.Tenant;
import com.sprintmodus.workitem_service.integration.TenantFixtures.User;

/**
 * Definition of Done of Phase 8 on the server: real STOMP clients over real WebSockets, through the real handshake check,
 * the real security chains, the real use cases and a real MySQL with the real tenant migrations. Users of one board see each
 * other's changes, comments and presence; a user of another tenant is refused at the handshake and never receives anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { "eureka.client.enabled=false" })
class BoardWebSocketIntegrationTest {

	private static final ProjectServiceStub PROJECT_SERVICE = ProjectServiceStub.INSTANCE;

	private static final long WAIT_SECONDS = 5;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.tenant.host", TenantFixtures.MYSQL::getHost);
		registry.add("spring.datasource.tenant.port", () -> TenantFixtures.MYSQL.getMappedPort(3306));
		registry.add("spring.datasource.tenant.username", () -> "root");
		registry.add("spring.datasource.tenant.password", () -> TenantFixtures.ROOT_PASSWORD);
		registry.add("spring.datasource.tenant.jdbc-parameters", () -> TenantFixtures.PARAMS);
		registry.add("sprintmodus.jwt.secret", () -> TenantFixtures.SECRET);
		registry.add("spring.cloud.discovery.client.simple.instances.project-service[0].uri", () -> "http://localhost:" + PROJECT_SERVICE.port());
	}

	@LocalServerPort
	int port;

	private final List<Board> open = new java.util.ArrayList<>();

	@AfterEach
	void closeBoards() {
		open.forEach(Board::close);
	}

	/** One user's open board: every broadcast it receives, and every private error it is sent. */
	private final class Board {

		final StompSession session;

		final BlockingQueue<String> events = new LinkedBlockingQueue<>();

		final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

		Board(StompSession session) {
			this.session = session;
		}

		void subscribe(String destination, BlockingQueue<String> into) throws Exception {
			session.subscribe(destination, new StompFrameHandler() {

				@Override
				public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
					return String.class;
				}

				@Override
				public void handleFrame(StompHeaders headers, Object payload) {
					into.add((String) payload);
				}

			});
		}

		void send(String destination, String json) {
			StompHeaders headers = new StompHeaders();
			headers.setDestination(destination);
			headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
			session.send(headers, json);
		}

		/** The next broadcast, parsed for JSON path lookups. */
		String next() throws InterruptedException {
			String message = events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
			assertThat(message).as("a board event within %d s", WAIT_SECONDS).isNotNull();
			return message;
		}

		String nextError() throws InterruptedException {
			String message = errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
			assertThat(message).as("a private error within %d s", WAIT_SECONDS).isNotNull();
			return message;
		}

		void assertNothingArrives() throws InterruptedException {
			Thread.sleep(700);
			assertThat(events).isEmpty();
			assertThat(errors).isEmpty();
		}

		void close() {
			if (session.isConnected()) {
				session.disconnect();
			}
		}

	}

	private static String type(String message) {
		return JsonPath.read(message, "$.type");
	}

	private static String url(int port, UUID tenantId, UUID project, String token) {
		return "ws://localhost:" + port + "/ws/tenant/" + tenantId + "/project/" + project + "/board" + (token == null ? "" : "?token=" + token);
	}

	/** Board frames are JSON; the tests read and write them as text, whatever their content type says. */
	private static final class AnyContentTypeStrings extends StringMessageConverter {

		@Override
		protected boolean supportsMimeType(MessageHeaders headers) {
			return true;
		}

	}

	private static WebSocketStompClient client() {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setMessageConverter(new AnyContentTypeStrings());
		return client;
	}

	private StompSession connect(String url) throws Exception {
		StompSession session = client().connectAsync(url, new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() {
		}).get(WAIT_SECONDS, TimeUnit.SECONDS);
		return session;
	}

	/**
	 * Connects as a user, subscribes to the board's private errors and topic, and announces itself, as the front end does.
	 * The first event a caller reads is its own {@code USER_CONNECTED}: it proves the subscriptions before it are live.
	 */
	private Board join(Tenant tenant, User user, UUID project) throws Exception {
		Board board = new Board(connect(url(port, tenant.id(), project, TenantFixtures.token(tenant, user))));
		open.add(board);
		board.subscribe("/user/queue/errors", board.errors);
		board.subscribe("/topic/tenant/" + tenant.id() + "/project/" + project, board.events);
		board.send(app(tenant, project, "connect"), "{}");
		return board;
	}

	private static String app(Tenant tenant, UUID project, String action) {
		return "/app/tenant/" + tenant.id() + "/project/" + project + "/" + action;
	}

	private UUID newTask(Tenant tenant, User user, UUID project, String title) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/work-items"))
				.header("Authorization", "Bearer " + TenantFixtures.token(tenant, user)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"projectCode\":\"%s\",\"type\":\"TASK\",\"title\":\"%s\"}".formatted(project, title))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(response.body(), "$.workItemCode"));
	}

	private void restPut(Tenant tenant, User user, String path, String json) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Authorization", "Bearer " + TenantFixtures.token(tenant, user)).header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
	}

	private void rest(Tenant tenant, User user, String path, String json) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Authorization", "Bearer " + TenantFixtures.token(tenant, user)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isBetween(200, 201);
	}

	private UUID newProject(Tenant tenant, String key) {
		UUID project = tenant.newProject(key);
		PROJECT_SERVICE.registerProject(project);
		return project;
	}

	private static String statusOf(Tenant tenant, UUID item) {
		return tenant.string("SELECT s.StatusCode FROM WorkItem w JOIN WorkItemStatus s ON s.StatusId = w.StatusId WHERE w.WorkItemCode = UUID_TO_BIN(?)", item.toString());
	}

	// ---------------------------------------------------------------- handshake

	@Test
	void aConnectionIsRefusedWithoutAValidTokenForItsOwnTenantAndAnExistingProject() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		Tenant other = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSA");

		assertThatThrownBy(() -> connect(url(port, tenant.id(), project, null))).hasMessageContaining("401");
		assertThatThrownBy(() -> connect(url(port, tenant.id(), project, "not-a-jwt"))).hasMessageContaining("401");
		// a perfectly valid token of another tenant, for this tenant's URL
		assertThatThrownBy(() -> connect(url(port, tenant.id(), project, TenantFixtures.token(other, other.owner())))).hasMessageContaining("403");
		assertThatThrownBy(() -> connect(url(port, tenant.id(), UUID.randomUUID(), TenantFixtures.token(tenant, tenant.owner())))).hasMessageContaining("404");
		// a user that is not a member of the tenant it names in the token
		User stranger = new User(UUID.randomUUID(), "x@y.test", "Stranger", "MEMBER");
		assertThatThrownBy(() -> connect(url(port, tenant.id(), project, TenantFixtures.token(tenant, stranger)))).hasMessageContaining("401");
	}

	@Test
	void aBrowserFromAnUnlistedOriginIsRefused() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSO");
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.setOrigin("http://evil.example");

		assertThatThrownBy(() -> client().connectAsync(url(port, tenant.id(), project, TenantFixtures.token(tenant, tenant.owner())), headers,
				new StompHeaders(), new StompSessionHandlerAdapter() {
				}).get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasMessageContaining("403");
	}

	// ---------------------------------------------------------------- real time

	@Test
	void aStatusChangeSentOverTheSocketIsPersistedAuditedAndSeenByEveryoneOnTheBoard() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSB");
		UUID item = newTask(tenant, tenant.owner(), project, "Wire it");
		Board ana = join(tenant, tenant.owner(), project);
		ana.next();
		Board mia = join(tenant, tenant.member(), project);
		assertThat(type(ana.next())).isEqualTo("USER_CONNECTED");
		String presence = mia.next();
		assertThat(type(presence)).isEqualTo("USER_CONNECTED");
		assertThat((List<?>) JsonPath.read(presence, "$.data.usersOnline")).hasSize(2);

		ana.send(app(tenant, project, "status-changed"), "{\"workItemCode\":\"%s\",\"status\":\"IN_PROGRESS\"}".formatted(item));

		for (Board board : List.of(ana, mia)) {
			String event = board.next();
			assertThat(type(event)).isEqualTo("ITEM_STATUS_CHANGED");
			assertThat((String) JsonPath.read(event, "$.data.workItemCode")).isEqualTo(item.toString());
			assertThat((String) JsonPath.read(event, "$.data.status.code")).isEqualTo("IN_PROGRESS");
			assertThat((Boolean) JsonPath.read(event, "$.data.status.isTerminal")).isFalse();
			assertThat((String) JsonPath.read(event, "$.data.changedBy.fullName")).isEqualTo("Olivia Owner");
		}
		assertThat(statusOf(tenant, item)).isEqualTo("IN_PROGRESS");
		assertThat(tenant.strings("""
				SELECT CONCAT(a.ChangeType, '|', a.OldValue, '|', a.NewValue) FROM WorkItemAudit a JOIN WorkItem w ON w.WorkItemId = a.WorkItemId
				WHERE w.WorkItemCode = UUID_TO_BIN(?) AND a.ChangeType = 'STATE_CHANGED'""", item.toString())).containsExactly("STATE_CHANGED|NEW|IN_PROGRESS");
	}

	@Test
	void aStatusChangeMadeOverRestReachesTheOpenBoardsToo() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSC");
		UUID item = newTask(tenant, tenant.owner(), project, "From REST");
		Board mia = join(tenant, tenant.member(), project);
		mia.next();

		rest(tenant, tenant.admin(), "/api/work-items/" + item + "/status", "{\"status\":\"IN_PROGRESS\"}");

		String event = mia.next();
		assertThat(type(event)).isEqualTo("ITEM_STATUS_CHANGED");
		assertThat((String) JsonPath.read(event, "$.data.changedBy.fullName")).isEqualTo("Adam Admin");
	}

	@Test
	void reorderingCardsTellsTheOpenBoardsToReloadTheColumn() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSO");
		UUID first = newTask(tenant, tenant.owner(), project, "First");
		UUID second = newTask(tenant, tenant.owner(), project, "Second");
		Board mia = join(tenant, tenant.member(), project);
		mia.next();

		restPut(tenant, tenant.admin(), "/api/work-items/" + second + "/rank", "{\"beforeCode\":\"%s\"}".formatted(first));

		String event = mia.next();
		assertThat(type(event)).isEqualTo("ITEMS_REORDERED");
		assertThat((String) JsonPath.read(event, "$.data.workItemCode")).isEqualTo(second.toString());
		assertThat((String) JsonPath.read(event, "$.data.status.code")).isEqualTo("NEW");
	}

	@Test
	void aDroppedCardIsAStatusChangeAndAnIllegalDropIsOnlyToldToTheSender() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSD");
		UUID item = newTask(tenant, tenant.owner(), project, "Drag me");
		Board ana = join(tenant, tenant.owner(), project);
		ana.next();
		Board mia = join(tenant, tenant.member(), project);
		ana.next();
		mia.next();

		ana.send(app(tenant, project, "item-moved"), "{\"workItemCode\":\"%s\",\"status\":\"IN_PROGRESS\",\"position\":2}".formatted(item));
		for (Board board : List.of(ana, mia)) {
			String event = board.next();
			assertThat(type(event)).isEqualTo("ITEM_MOVED");
			assertThat((String) JsonPath.read(event, "$.data.fromStatus")).isEqualTo("NEW");
			assertThat((String) JsonPath.read(event, "$.data.status.code")).isEqualTo("IN_PROGRESS");
			assertThat((Integer) JsonPath.read(event, "$.data.position")).isEqualTo(2);
		}

		// NEW is not reachable from IN_PROGRESS in one step... but DONE is not either: the workflow says no
		ana.send(app(tenant, project, "item-moved"), "{\"workItemCode\":\"%s\",\"status\":\"DONE\",\"position\":0}".formatted(item));
		String error = ana.nextError();
		assertThat(type(error)).isEqualTo("ERROR");
		assertThat((String) JsonPath.read(error, "$.data.code")).isEqualTo("INVALID_TRANSITION");
		assertThat(statusOf(tenant, item)).isEqualTo("IN_PROGRESS");
		mia.assertNothingArrives();
		assertThat(ana.events).isEmpty();
	}

	@Test
	void aCommentSentOverTheSocketIsPersistedAndBroadcast() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSE");
		UUID item = newTask(tenant, tenant.owner(), project, "Talk about it");
		Board ana = join(tenant, tenant.owner(), project);
		ana.next();
		Board mia = join(tenant, tenant.member(), project);
		ana.next();
		mia.next();

		mia.send(app(tenant, project, "comment-added"), "{\"workItemCode\":\"%s\",\"content\":\"Looks good\"}".formatted(item));

		for (Board board : List.of(ana, mia)) {
			String event = board.next();
			assertThat(type(event)).isEqualTo("COMMENT_ADDED");
			assertThat((String) JsonPath.read(event, "$.data.comment.content")).isEqualTo("Looks good");
			assertThat((String) JsonPath.read(event, "$.data.comment.commentCode")).isNotBlank();
			assertThat((String) JsonPath.read(event, "$.data.comment.author.fullName")).isEqualTo("Mia Member");
		}
		assertThat(tenant.strings("SELECT c.Content FROM Comment c JOIN WorkItem w ON w.WorkItemId = c.WorkItemId WHERE w.WorkItemCode = UUID_TO_BIN(?)",
				item.toString())).containsExactly("Looks good");
	}

	@Test
	void aWorkItemOfAnotherProjectCannotBeTouchedFromThisBoard() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID boardProject = newProject(tenant, "WSF");
		UUID otherProject = newProject(tenant, "WSG");
		UUID foreign = newTask(tenant, tenant.owner(), otherProject, "Elsewhere");
		Board ana = join(tenant, tenant.owner(), boardProject);
		ana.next();

		ana.send(app(tenant, boardProject, "status-changed"), "{\"workItemCode\":\"%s\",\"status\":\"IN_PROGRESS\"}".formatted(foreign));

		assertThat((String) JsonPath.read(ana.nextError(), "$.data.code")).isEqualTo("WORK_ITEM_NOT_FOUND");
		assertThat(statusOf(tenant, foreign)).isEqualTo("NEW");
	}

	@Test
	void typingIsBroadcastToTheOthersButNotFloodedOnEveryKeystroke() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSH");
		UUID item = newTask(tenant, tenant.owner(), project, "Type here");
		Board ana = join(tenant, tenant.owner(), project);
		ana.next();
		Board mia = join(tenant, tenant.member(), project);
		ana.next();
		mia.next();

		for (int i = 0; i < 5; i++) {
			mia.send(app(tenant, project, "user-typing"), "{\"workItemCode\":\"%s\"}".formatted(item));
		}

		String event = ana.next();
		assertThat(type(event)).isEqualTo("USER_TYPING");
		assertThat((String) JsonPath.read(event, "$.data.user.email")).isEqualTo(tenant.member().email());
		Thread.sleep(700);
		assertThat(ana.events.stream().filter(m -> "USER_TYPING".equals(type(m)))).isEmpty();
	}

	@Test
	void presenceFollowsConnectionsIncludingOnesThatDropWithoutGoodbye() throws Exception {
		Tenant tenant = TenantFixtures.newTenant();
		UUID project = newProject(tenant, "WSI");
		Board ana = join(tenant, tenant.owner(), project);
		ana.next();
		Board mia = join(tenant, tenant.member(), project);
		ana.next();
		mia.next();
		Board admin = join(tenant, tenant.admin(), project);
		for (Board board : List.of(ana, mia, admin)) {
			assertThat((List<?>) JsonPath.read(board.next(), "$.data.usersOnline")).hasSize(3);
		}

		mia.send(app(tenant, project, "disconnect"), "{}");
		String goodbye = ana.next();
		assertThat(type(goodbye)).isEqualTo("USER_DISCONNECTED");
		assertThat((List<?>) JsonPath.read(goodbye, "$.data.usersOnline")).hasSize(2);
		admin.next();

		// closing the connection abruptly is announced too, and Mia's earlier goodbye is not announced twice
		admin.session.disconnect();
		String dropped = ana.next();
		assertThat(type(dropped)).isEqualTo("USER_DISCONNECTED");
		assertThat((String) JsonPath.read(dropped, "$.data.user.email")).isEqualTo(tenant.admin().email());
		assertThat((List<?>) JsonPath.read(dropped, "$.data.usersOnline")).hasSize(1);
	}

	// ---------------------------------------------------------------- isolation

	@Test
	void aTenantNeverReceivesAnotherTenantsEvents() throws Exception {
		Tenant acme = TenantFixtures.newTenant();
		Tenant globex = TenantFixtures.newTenant();
		UUID acmeProject = newProject(acme, "ACM");
		UUID globexProject = newProject(globex, "GLX");
		UUID item = newTask(acme, acme.owner(), acmeProject, "Acme only");
		Board acmeUser = join(acme, acme.owner(), acmeProject);
		acmeUser.next();
		Board globexUser = join(globex, globex.owner(), globexProject);
		globexUser.next();
		acmeUser.assertNothingArrives();

		acmeUser.send(app(acme, acmeProject, "status-changed"), "{\"workItemCode\":\"%s\",\"status\":\"IN_PROGRESS\"}".formatted(item));
		acmeUser.next();

		globexUser.assertNothingArrives();
	}

	@Test
	void subscribingToAnotherTenantsTopicOrPublishingToItClosesTheConnection() throws Exception {
		Tenant acme = TenantFixtures.newTenant();
		Tenant globex = TenantFixtures.newTenant();
		UUID acmeProject = newProject(acme, "AC2");
		UUID globexProject = newProject(globex, "GL2");
		UUID item = newTask(acme, acme.owner(), acmeProject, "Acme only");

		Board snooper = join(globex, globex.owner(), globexProject);
		snooper.next();
		snooper.session.subscribe("/topic/tenant/" + acme.id() + "/project/" + acmeProject, new StompSessionHandlerAdapter() {
		});
		awaitClosed(snooper);

		Board forger = join(globex, globex.owner(), globexProject);
		forger.next();
		forger.send(app(acme, acmeProject, "status-changed"), "{\"workItemCode\":\"%s\",\"status\":\"IN_PROGRESS\"}".formatted(item));
		awaitClosed(forger);
		assertThat(statusOf(acme, item)).isEqualTo("NEW");
	}

	private static void awaitClosed(Board board) throws InterruptedException {
		for (int i = 0; i < 50 && board.session.isConnected(); i++) {
			Thread.sleep(100);
		}
		assertThat(board.session.isConnected()).as("the server closed the connection").isFalse();
	}

}
