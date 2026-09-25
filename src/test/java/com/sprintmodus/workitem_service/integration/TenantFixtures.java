package com.sprintmodus.workitem_service.integration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.sprintmodus.common_lib.tenant.TenantDatabaseNameResolver;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * A real MySQL with real tenant databases, built the way onboarding builds them (named from the tenant id, migrated with
 * the tenant Flyway scripts, so the default workflows are seeded), and tokens built the way auth-service builds them.
 * Projects and sprints belong to project-service, so the tests insert them with SQL.
 */
final class TenantFixtures {

	static final String ROOT_PASSWORD = "test";

	static final String PARAMS = "connectionTimeZone=UTC&allowPublicKeyRetrieval=true";

	static final String SECRET = "integration-test-secret-that-is-long-enough-for-hs512-0123456789-0123456789";

	static final String ISSUER = "sprintmodus-auth";

	private static final TenantDatabaseNameResolver NAMES = new TenantDatabaseNameResolver();

	/** One container for the whole test run, started on first use and removed by Testcontainers when the JVM exits. */
	static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:9.7")
			.withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
			.withExposedPorts(3306)
			// the entrypoint runs a temporary socket-only server first; only the final server logs port 3306
			.waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306 .*", 1));

	static {
		MYSQL.start();
	}

	private TenantFixtures() {
	}

	static String url(String database) {
		return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database + "?" + PARAMS;
	}

	static Connection connect(String database) throws SQLException {
		return DriverManager.getConnection(url(database), "root", ROOT_PASSWORD);
	}

	record User(UUID code, String email, String fullName, String role) {
	}

	/** A tenant with its own database and three users: an OWNER, an ADMIN and a MEMBER. */
	record Tenant(UUID id, String database, User owner, User admin, User member) {

		void execute(String sql, Object... parameters) {
			try (Connection connection = connect(database); PreparedStatement statement = connection.prepareStatement(sql)) {
				for (int i = 0; i < parameters.length; i++) {
					statement.setObject(i + 1, parameters[i]);
				}
				statement.execute();
			}
			catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}

		/** The first column of every row of a query in this tenant's database, as strings. */
		List<String> strings(String sql, Object... parameters) {
			try (Connection connection = connect(database); PreparedStatement statement = connection.prepareStatement(sql)) {
				for (int i = 0; i < parameters.length; i++) {
					statement.setObject(i + 1, parameters[i]);
				}
				List<String> values = new ArrayList<>();
				try (ResultSet rs = statement.executeQuery()) {
					while (rs.next()) {
						values.add(rs.getString(1));
					}
				}
				return values;
			}
			catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}

		String string(String sql, Object... parameters) {
			return strings(sql, parameters).getFirst();
		}

		/** Inserts a project the way project-service does (with its numbering sequence) and returns its code. */
		UUID newProject(String key) {
			execute("INSERT INTO Project (Name, `Key`, CreatedBy) SELECT ?, ?, UserId FROM `User` WHERE Role = 'OWNER'", "Project " + key, key);
			execute("INSERT INTO WorkItemSequence (ProjectId) SELECT ProjectId FROM Project WHERE `Key` = ?", key);
			return UUID.fromString(string("SELECT BIN_TO_UUID(ProjectCode) FROM Project WHERE `Key` = ?", key));
		}

		/** Inserts a sprint the way project-service does and returns its code. */
		UUID newSprint(UUID projectCode, String name, String status) {
			UUID sprintCode = UUID.randomUUID();
			execute("""
					INSERT INTO Sprint (SprintCode, ProjectId, Name, Status, ConfiguredDays, StartDate, EndDate, CreatedBy)
					SELECT UUID_TO_BIN(?), p.ProjectId, ?, ?, 14, '2026-01-05', '2026-01-19', p.CreatedBy
					FROM Project p WHERE p.ProjectCode = UUID_TO_BIN(?)""", sprintCode.toString(), name, status, projectCode.toString());
			return sprintCode;
		}

	}

	static Tenant newTenant() {
		UUID tenantId = UUID.randomUUID();
		String database = NAMES.resolve(tenantId);
		try (Connection connection = DriverManager.getConnection(url(""), "root", ROOT_PASSWORD);
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		Flyway.configure().dataSource(url(database), "root", ROOT_PASSWORD).locations("classpath:db/migration/tenant").load().migrate();
		String suffix = database.substring(7, 15);
		Tenant tenant = new Tenant(tenantId, database, new User(UUID.randomUUID(), "owner@" + suffix + ".test", "Olivia Owner", "OWNER"),
				new User(UUID.randomUUID(), "admin@" + suffix + ".test", "Adam Admin", "ADMIN"),
				new User(UUID.randomUUID(), "member@" + suffix + ".test", "Mia Member", "MEMBER"));
		for (User user : List.of(tenant.owner(), tenant.admin(), tenant.member())) {
			tenant.execute("INSERT INTO `User` (UserCode, Email, PasswordHash, FullName, Role) VALUES (UUID_TO_BIN(?), ?, 'hash', ?, ?)",
					user.code().toString(), user.email(), user.fullName(), user.role());
		}
		return tenant;
	}

	/** A token built exactly as auth-service builds it. */
	static String token(Tenant tenant, User user) {
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder().issuer(ISSUER).subject(user.code().toString()).issuedAt(new Date())
				.expiration(Date.from(Instant.now().plusSeconds(3600))).claim("email", user.email())
				.claim("tenantId", tenant.id().toString()).claim("organizationCode", "org").claim("role", user.role())
				.claim("plan", "FREE").claim("maxProjects", 5).claim("maxUsers", 5).claim("maxStorageMB", 100)
				.signWith(key, Jwts.SIG.HS512).compact();
	}

}
