package cc.ccmlab.prudence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/// Every Flyway migration applied to a real, empty PostgreSQL database, then Hibernate validating
/// every entity against the result, exactly as production starts. The other tests build their
/// schema from the entities on H2 and never run a migration, so a migration that fails, or one
/// that leaves the schema out of step with the entities, would only show at deployment, as a
/// Prudence that refuses to start.
///
/// Needs PRUDENCE_TEST_POSTGRES_URL (with _USER and _PASSWORD), an empty database: CI provides
/// one; locally, for example
///
///   createdb prudence_migrations
///   PRUDENCE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/prudence_migrations \
///     mvn test -Dtest=PostgresMigrationsTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.flyway.enabled=true",
      "spring.jpa.hibernate.ddl-auto=validate",
      "prudence.ernest.base-url=http://127.0.0.1:1",
      "prudence.opa.base-url=http://127.0.0.1:1"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PRUDENCE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PostgresMigrationsTest {

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("PRUDENCE_TEST_POSTGRES_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_USER", "postgres"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_PASSWORD", ""));
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  void everyMigrationAppliesAndTheEntitiesMatchTheSchema() throws IOException {
    // The context started, so Flyway migrated and Hibernate validated. Check nothing was skipped.
    long files;
    try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
      files = migrations.filter(p -> p.getFileName().toString().matches("V\\d+__.+\\.sql")).count();
    }
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success and version is not null",
            Integer.class);
    assertEquals(files, applied.longValue(), "applied migrations");
    Integer failed =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where not success", Integer.class);
    assertEquals(0, failed);
    assertTrue(files > 0);
  }
}
