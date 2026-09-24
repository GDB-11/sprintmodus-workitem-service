package com.sprintmodus.workitem_service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.common_lib.tenant.TenantContext;
import com.sprintmodus.common_lib.tenant.TenantDatabaseNameResolver;
import com.sprintmodus.common_lib.tenant.TenantRoutingDataSource;
import com.sprintmodus.workitem_service.adapter.feign.client.ProjectServiceClient;
import com.sprintmodus.workitem_service.adapter.feign.dto.ProjectDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.SprintDto;
import com.sprintmodus.workitem_service.adapter.feign.dto.VelocityUpdateRequest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;

/**
 * Definition of Done of Phase 3: a request carrying a valid JWT is routed to the right tenant database. Two tenant
 * databases are built with the real tenant migrations and each holds a different user; every request must see only
 * its own tenant's data.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = { "eureka.client.enabled=false",
		"logging.level.com.sprintmodus.common_lib.security=DEBUG" })
@AutoConfigureMockMvc
@Import(TenantRoutingIntegrationTest.RoutingProbe.class)
class TenantRoutingIntegrationTest {

	private static final String ROOT_PASSWORD = "test";

	private static final String PARAMS = "connectionTimeZone=UTC&allowPublicKeyRetrieval=true";

	private static final String SECRET = "integration-test-secret-that-is-long-enough-for-hs512-0123456789-0123456789";

	private static final String ISSUER = "sprintmodus-auth";

	@Container
	static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:9.7")
			.withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
			.withExposedPorts(3306)
			// the entrypoint runs a temporary socket-only server first; only the final server logs port 3306
			.waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306 .*", 1));

	/** Stands in for project-service: records what it is called with and answers with canned JSON. */
	private record StubCall(String method, String path, String authorization, String body) {
	}

	private static final List<StubCall> STUB_CALLS = new CopyOnWriteArrayList<>();

	private static final HttpServer PROJECT_SERVICE = startProjectServiceStub();

	private static HttpServer startProjectServiceStub() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				STUB_CALLS.add(new StubCall(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
						exchange.getRequestHeaders().getFirst("Authorization"), body));
				String path = exchange.getRequestURI().getPath();
				String json = path.startsWith("/api/projects/")
						? "{\"projectCode\":\"" + path.substring("/api/projects/".length()) + "\",\"name\":\"Web\",\"key\":\"WEB\"}"
						: path.endsWith("/velocity") ? ""
						: "{\"sprintCode\":\"" + path.substring("/api/sprints/".length()) + "\",\"projectCode\":\"" + UUID.randomUUID()
								+ "\",\"name\":\"Sprint 1\",\"status\":\"ACTIVE\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-19\",\"velocity\":8}";
				byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(json.isEmpty() ? 204 : 200, json.isEmpty() ? -1 : bytes.length);
				if (!json.isEmpty()) {
					exchange.getResponseBody().write(bytes);
				}
				exchange.close();
			});
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A tenant with its own database and one user. */
	private record Tenant(UUID id, UUID userCode, String email, String database) {
	}

	private static final TenantDatabaseNameResolver NAMES = new TenantDatabaseNameResolver();

	private static Tenant a;

	private static Tenant b;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.tenant.host", MYSQL::getHost);
		registry.add("spring.datasource.tenant.port", () -> MYSQL.getMappedPort(3306));
		registry.add("spring.datasource.tenant.username", () -> "root");
		registry.add("spring.datasource.tenant.password", () -> ROOT_PASSWORD);
		registry.add("spring.datasource.tenant.jdbc-parameters", () -> PARAMS);
		registry.add("sprintmodus.jwt.secret", () -> SECRET);
		// Eureka is off in tests; resolve project-service to the stub through the load balancer instead
		registry.add("spring.cloud.discovery.client.simple.instances.project-service[0].uri",
				() -> "http://localhost:" + PROJECT_SERVICE.getAddress().getPort());
	}

	@BeforeAll
	static void seedTwoTenants() throws SQLException {
		a = seedTenant("ana@alpha.test");
		b = seedTenant("bob@beta.test");
	}

	/** Creates the tenant database exactly like onboarding does: named from the tenant id, migrated, with its owner. */
	private static Tenant seedTenant(String email) throws SQLException {
		UUID tenantId = UUID.randomUUID();
		UUID userCode = UUID.randomUUID();
		String database = NAMES.resolve(tenantId);
		try (Connection connection = root(""); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		}
		Flyway.configure().dataSource(url(database), "root", ROOT_PASSWORD).locations("classpath:db/migration/tenant").load()
				.migrate();
		try (Connection connection = root(database);
				PreparedStatement insert = connection.prepareStatement(
						"INSERT INTO `User` (UserCode, Email, PasswordHash, FullName, Role) VALUES (UUID_TO_BIN(?), ?, 'hash', 'Someone', 'OWNER')")) {
			insert.setString(1, userCode.toString());
			insert.setString(2, email);
			insert.executeUpdate();
		}
		return new Tenant(tenantId, userCode, email, database);
	}

	private static String url(String database) {
		return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database + "?" + PARAMS;
	}

	private static Connection root(String database) throws SQLException {
		return DriverManager.getConnection(url(database), "root", ROOT_PASSWORD);
	}

	/** A token built exactly as auth-service builds it. */
	private static String token(Tenant tenant, UUID userCode, Instant expiresAt, String secret) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder().issuer(ISSUER).subject(userCode.toString()).issuedAt(new Date())
				.expiration(Date.from(expiresAt)).claim("email", tenant.email()).claim("tenantId", tenant.id().toString())
				.claim("organizationCode", "org").claim("role", "OWNER").claim("plan", "FREE").claim("maxProjects", 1)
				.claim("maxUsers", 5).claim("maxStorageMB", 100).signWith(key, Jwts.SIG.HS512).compact();
	}

	private static String tokenOf(Tenant tenant) {
		return token(tenant, tenant.userCode(), Instant.now().plusSeconds(3600), SECRET);
	}

	/** Answers with what the request actually reached, so the test can see the routing. */
	@RestController
	@RequestMapping("/api/_probe")
	static class RoutingProbe {

		private final JdbcTemplate jdbc;

		private final ProjectServiceClient projects;

		RoutingProbe(@Qualifier("tenantDataSource") DataSource tenantDataSource, ProjectServiceClient projects) {
			this.jdbc = new JdbcTemplate(tenantDataSource);
			this.projects = projects;
		}

		@GetMapping("/project/{code}")
		ProjectDto project(@PathVariable UUID code) {
			return projects.getProject(code);
		}

		@GetMapping("/sprint/{code}")
		SprintDto sprint(@PathVariable UUID code) {
			return projects.getSprint(code);
		}

		@GetMapping("/velocity/{code}")
		void velocity(@PathVariable UUID code) {
			projects.updateSprintVelocity(code, new VelocityUpdateRequest(13));
		}

		@GetMapping("/whoami")
		Map<String, Object> whoami() {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
			return Map.of(
					"database", jdbc.queryForObject("SELECT DATABASE()", String.class),
					"users", jdbc.queryForList("SELECT Email FROM `User`", String.class),
					"contextTenant", TenantContext.requireCurrentTenant().toString(),
					"contextMaxProjects", TenantContext.getMaxProjects().orElseThrow(),
					"principalTenant", user.tenantId().toString(),
					"role", authentication.getAuthorities().iterator().next().getAuthority());
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	@Qualifier("tenantDataSource")
	TenantRoutingDataSource tenantDataSource;

	@Autowired
	EntityManager entityManager;

	@Autowired
	TransactionTemplate transaction;

	private ResultActions call(String path, String token) throws Exception {
		var request = get(path);
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return mvc.perform(request);
	}

	@Test
	void eachTenantsRequestReachesOnlyItsOwnDatabase() throws Exception {
		call("/api/_probe/whoami", tokenOf(a)).andExpect(status().isOk())
				.andExpect(jsonPath("$.database").value(a.database()))
				.andExpect(jsonPath("$.users[0]").value("ana@alpha.test"))
				.andExpect(jsonPath("$.users.length()").value(1))
				.andExpect(jsonPath("$.contextTenant").value(a.id().toString()))
				.andExpect(jsonPath("$.principalTenant").value(a.id().toString()))
				.andExpect(jsonPath("$.contextMaxProjects").value(1))
				.andExpect(jsonPath("$.role").value("ROLE_OWNER"));

		call("/api/_probe/whoami", tokenOf(b)).andExpect(status().isOk())
				.andExpect(jsonPath("$.database").value(b.database()))
				.andExpect(jsonPath("$.users[0]").value("bob@beta.test"))
				.andExpect(jsonPath("$.users.length()").value(1));

		assertThat(a.database()).isNotEqualTo(b.database());
	}

	@Test
	void concurrentRequestsOfDifferentTenantsNeverSeeEachOthersData() throws Exception {
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < 80; i++) {
				Tenant tenant = i % 2 == 0 ? a : b;
				String token = tokenOf(tenant);
				results.add(executor.submit(() -> {
					String body = call("/api/_probe/whoami", token).andExpect(status().isOk()).andReturn().getResponse()
							.getContentAsString();
					return tenant.database() + "|" + body;
				}));
			}
			for (Future<String> result : results) {
				String[] expectedAndBody = result.get().split("\\|", 2);
				assertThat(expectedAndBody[1]).contains("\"database\":\"" + expectedAndBody[0] + "\"");
			}
		}
	}

	@Test
	void logsTheRoutingKeyOfEveryAuthenticatedRequest(CapturedOutput output) throws Exception {
		call("/api/_probe/whoami", tokenOf(a)).andExpect(status().isOk());
		call("/api/_probe/whoami", tokenOf(b)).andExpect(status().isOk());

		assertThat(output.getAll()).contains("Authenticated user " + a.userCode() + " for tenant " + a.id() + ", routing to database " + a.database())
				.contains("Authenticated user " + b.userCode() + " for tenant " + b.id() + ", routing to database " + b.database());
	}

	private String nativeString(String sql, String parameter) {
		var query = entityManager.createNativeQuery(sql);
		if (parameter != null) {
			query.setParameter("code", parameter);
		}
		return String.valueOf(query.getSingleResult());
	}

	/** Runs a native query inside a transaction that begins on the given tenant's database. */
	private String inTenant(UUID tenantId, String sql, String parameter) {
		return TenantContext.callAs(tenantId, () -> transaction.execute(_ -> nativeString(sql, parameter)));
	}

	@Test
	void nativeQueriesRouteToTheTenantDatabaseToo() {
		for (Tenant tenant : List.of(a, b, a)) {
			assertThat(inTenant(tenant.id(), "SELECT DATABASE()", null)).isEqualTo(tenant.database());
		}
	}

	@Test
	void transactionsBeginOnTheTenantDatabaseAndRollBackThere() {
		String name = "SELECT FullName FROM `User` WHERE UserCode = UUID_TO_BIN(:code)";
		String code = a.userCode().toString();

		assertThatThrownBy(() -> TenantContext.callAs(a.id(), () -> transaction.execute(_ -> {
			entityManager.createNativeQuery("UPDATE `User` SET FullName = 'Changed' WHERE UserCode = UUID_TO_BIN(:code)")
					.setParameter("code", code).executeUpdate();
			assertThat(nativeString(name, code)).isEqualTo("Changed");
			throw new IllegalStateException("boom");
		}))).hasMessage("boom");

		assertThat(inTenant(a.id(), name, code)).isEqualTo("Someone");
		// the same user code in the other tenant's database does not exist
		assertThat(inTenant(b.id(), "SELECT COUNT(*) FROM `User` WHERE UserCode = UUID_TO_BIN(:code)", code)).isEqualTo("0");
	}

	@Test
	void keepsOnePoolPerTenantAndClearsTheContextAfterEveryRequest() throws Exception {
		call("/api/_probe/whoami", tokenOf(a)).andExpect(status().isOk());
		call("/api/_probe/whoami", tokenOf(a)).andExpect(status().isOk());
		call("/api/_probe/whoami", tokenOf(b)).andExpect(status().isOk());

		assertThat(tenantDataSource.poolCount()).isGreaterThanOrEqualTo(2);
		// MockMvc runs on this thread, so a leaked context would still be here
		assertThat(TenantContext.getCurrentTenant()).isEmpty();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void requestsWithoutAValidTokenAreRejectedBeforeReachingAnyDatabase() throws Exception {
		call("/api/_probe/whoami", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		call("/api/_probe/whoami", "garbage").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		call("/api/_probe/whoami", token(a, a.userCode(), Instant.now().minusSeconds(5), SECRET)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
		call("/api/_probe/whoami", token(a, a.userCode(), Instant.now().plusSeconds(60),
				"a-different-secret-that-is-long-enough-for-hs512-0123456789-0123456789-abcdef"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	@Test
	void aTokenNamingATenantItsUserDoesNotBelongToIsRejected() throws Exception {
		// Ana's user code, but a token that claims tenant B: B's database has no such user
		String forged = token(b, a.userCode(), Instant.now().plusSeconds(3600), SECRET);

		call("/api/_probe/whoami", forged).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	@Test
	void aDeactivatedUserLosesAccessImmediatelyAndRegainsItWhenReactivated() throws Exception {
		Tenant tenant = seedTenant("temp@gamma.test");
		String token = tokenOf(tenant);
		call("/api/_probe/whoami", token).andExpect(status().isOk());

		try (Connection connection = root(tenant.database()); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE `User` SET IsActive = FALSE");
			call("/api/_probe/whoami", token).andExpect(status().isUnauthorized());

			statement.execute("UPDATE `User` SET IsActive = TRUE, DeletedAt = NOW()");
			call("/api/_probe/whoami", token).andExpect(status().isUnauthorized());

			statement.execute("UPDATE `User` SET DeletedAt = NULL");
		}
		call("/api/_probe/whoami", token).andExpect(status().isOk());
	}

	@Test
	void aTokenForATenantWhoseDatabaseIsMissingIsAGeneric500() throws Exception {
		Tenant ghost = new Tenant(UUID.randomUUID(), UUID.randomUUID(), "ghost@nowhere.test", "unused");

		String body = call("/api/_probe/whoami", tokenOf(ghost)).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("tenant_").doesNotContain(ghost.id().toString());
	}

	@Test
	void everythingOutsideApiIsClosed() throws Exception {
		call("/actuator/health", null).andExpect(status().isUnauthorized());
		call("/actuator/health", tokenOf(a)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		call("/", tokenOf(a)).andExpect(status().isForbidden());
	}


	// ------------------------------------------------------------------ calls to project-service

	@Test
	void callsToProjectServiceCarryTheCallersTokenAndDecodeItsAnswers() throws Exception {
		STUB_CALLS.clear();
		UUID projectCode = UUID.randomUUID();
		UUID sprintCode = UUID.randomUUID();
		String token = tokenOf(a);

		call("/api/_probe/project/" + projectCode, token).andExpect(status().isOk())
				.andExpect(jsonPath("$.projectCode").value(projectCode.toString())).andExpect(jsonPath("$.key").value("WEB"));
		call("/api/_probe/sprint/" + sprintCode, token).andExpect(status().isOk())
				.andExpect(jsonPath("$.sprintCode").value(sprintCode.toString())).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.startDate").value("2026-01-05")).andExpect(jsonPath("$.velocity").value(8));
		call("/api/_probe/velocity/" + sprintCode, token).andExpect(status().isOk());

		assertThat(STUB_CALLS).extracting(StubCall::method, StubCall::path).containsExactly(
				org.assertj.core.groups.Tuple.tuple("GET", "/api/projects/" + projectCode),
				org.assertj.core.groups.Tuple.tuple("GET", "/api/sprints/" + sprintCode),
				org.assertj.core.groups.Tuple.tuple("PUT", "/api/sprints/" + sprintCode + "/velocity"));
		assertThat(STUB_CALLS).extracting(StubCall::authorization).containsOnly("Bearer " + token);
		assertThat(STUB_CALLS.getLast().body()).isEqualTo("{\"velocity\":13}");
	}

	@Test
	void eachCallForwardsItsOwnCallersTokenNeverAnotherTenants() throws Exception {
		STUB_CALLS.clear();
		String tokenA = tokenOf(a);
		String tokenB = tokenOf(b);

		call("/api/_probe/project/" + UUID.randomUUID(), tokenA).andExpect(status().isOk());
		call("/api/_probe/project/" + UUID.randomUUID(), tokenB).andExpect(status().isOk());

		assertThat(STUB_CALLS).extracting(StubCall::authorization).containsExactly("Bearer " + tokenA, "Bearer " + tokenB);
	}

}
