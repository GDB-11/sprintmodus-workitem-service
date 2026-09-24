package com.sprintmodus.workitem_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the persistence rules of this project, so they hold in every phase, not just when someone remembers them:
 * <ul>
 * <li><b>Every query is native SQL.</b> JPA is used to map results to entities, but the SQL is always written by hand:
 * no JPQL, no derived query methods, no Criteria API, no {@code save}/{@code findById}/{@code persist} that would
 * generate SQL nobody wrote.</li>
 * <li>Repository interfaces are ports in {@code application.port.persistence}; their implementations, the entities
 * and the native-query interfaces live in {@code infrastructure.persistence}.</li>
 * <li>Only {@code infrastructure} touches persistence technology (JPA, Spring Data, Hibernate, JDBC).</li>
 * </ul>
 * It reads the sources as text, so it needs no bytecode tooling and names the offending file when it fails.
 */
class ArchitectureRulesTest {

	private static final Path MAIN = Path.of("src/main/java");

	/** Persistence technology: allowed only under {@code infrastructure}. */
	private static final Pattern PERSISTENCE_TECHNOLOGY = Pattern.compile(
			"import (jakarta\\.persistence\\.|javax\\.persistence\\.|org\\.hibernate\\.|org\\.springframework\\.data\\.|org\\.springframework\\.jdbc\\.|java\\.sql\\.|javax\\.sql\\.)|\\b(JdbcClient|JdbcTemplate|EntityManager)\\b");

	/** Ways to get SQL that nobody wrote. */
	private static final Pattern GENERATED_SQL = Pattern.compile(
			"\\bJpaRepository\\b|\\bCrudRepository\\b|\\bListCrudRepository\\b|\\bPagingAndSortingRepository\\b|\\bJpaSpecificationExecutor\\b|\\bQueryByExampleExecutor\\b"
					+ "|@NamedQuery|@NamedNativeQuery|@GeneratedValue|@Table\\(.*\\bindexes\\b"
					+ "|createQuery\\(|createNamedQuery\\(|CriteriaBuilder|\\.persist\\(|\\.merge\\(|\\.remove\\(|\\.getReference\\(|entityManager\\.find\\(");

	/** A {@code @Query} that does not start with {@code nativeQuery = true} is JPQL (or forgot to say native). */
	private static final Pattern NON_NATIVE_QUERY = Pattern.compile("@Query\\((?!\\s*nativeQuery\\s*=\\s*true)");

	private static final Pattern REPOSITORY_IMPLEMENTATION = Pattern.compile("\\bimplements\\s+\\w*Repository\\b");

	private static List<Path> sources() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			return files.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}

	private static String text(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String normalized(Path path) {
		return path.toString().replace('\\', '/');
	}

	private static long count(Pattern pattern, String text) {
		return pattern.matcher(text).results().count();
	}

	@Test
	void everyQueryIsNativeSql() throws IOException {
		List<String> offenders = sources().stream().filter(path -> NON_NATIVE_QUERY.matcher(text(path)).find())
				.map(ArchitectureRulesTest::normalized).toList();

		assertThat(offenders).as("@Query without nativeQuery = true (JPQL is not allowed)").isEmpty();
	}

	@Test
	void noSqlIsGeneratedForUs() throws IOException {
		List<String> offenders = sources().stream().filter(path -> GENERATED_SQL.matcher(text(path)).find())
				.map(ArchitectureRulesTest::normalized).toList();

		assertThat(offenders).as("derived/generated SQL: extend the bare Repository and write @Query(nativeQuery = true)")
				.isEmpty();
	}

	@Test
	void everyMethodOfASpringDataInterfaceIsAnExplicitNativeQuery() throws IOException {
		Pattern method = Pattern.compile("\\)\\s*;");
		Pattern query = Pattern.compile("@Query\\(");
		List<String> springDataInterfaces = sources().stream().filter(path -> text(path).contains("extends Repository<"))
				.map(ArchitectureRulesTest::normalized).toList();

		// a method without @Query would be a derived query: the number of declarations must equal the number of queries
		assertThat(springDataInterfaces).allSatisfy(path -> assertThat(count(method, text(Path.of(path))))
				.as(path + ": methods vs @Query").isEqualTo(count(query, text(Path.of(path)))));
	}

	@Test
	void onlyInfrastructureTouchesPersistenceTechnology() throws IOException {
		List<String> offenders = sources().stream().filter(path -> !normalized(path).contains("/infrastructure/"))
				.filter(path -> PERSISTENCE_TECHNOLOGY.matcher(text(path)).find()).map(ArchitectureRulesTest::normalized)
				.toList();

		assertThat(offenders).as("sources outside infrastructure that use JPA/Spring Data/JDBC").isEmpty();
	}

	@Test
	void repositoryImplementationsLiveInInfrastructurePersistence() throws IOException {
		List<String> offenders = sources().stream().filter(path -> REPOSITORY_IMPLEMENTATION.matcher(text(path)).find())
				.filter(path -> !normalized(path).contains("/infrastructure/persistence/"))
				.map(ArchitectureRulesTest::normalized).toList();

		assertThat(offenders).as("repository implementations outside infrastructure/persistence").isEmpty();
	}

	@Test
	void repositoryPortsAreInterfacesInTheApplicationLayer() throws IOException {
		List<String> ports = sources().stream().map(ArchitectureRulesTest::normalized)
				.filter(path -> path.contains("/application/port/persistence/")).toList();

		assertThat(ports).allSatisfy(path -> assertThat(text(Path.of(path))).contains("public interface ")
				.doesNotContain("@Repository").doesNotContain("@Query"));
	}

}
