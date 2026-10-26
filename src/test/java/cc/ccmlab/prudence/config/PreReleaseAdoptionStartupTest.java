package cc.ccmlab.prudence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/// Prudence starting on a database the pre-release migrations built: Spring Boot runs
/// PreReleaseAdoption before Flyway migrates, so the application starts, Hibernate validates the
/// schema, and the history is the baseline's. Needs PRUDENCE_TEST_POSTGRES_URL, like
/// PostgresMigrationsTest; works in the schema `startup_adoption`.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.schemas=startup_adoption",
      "spring.flyway.default-schema=startup_adoption",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.default_schema=startup_adoption",
      "prudence.ernest.base-url=http://127.0.0.1:1",
      "prudence.opa.base-url=http://127.0.0.1:1"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PRUDENCE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PreReleaseAdoptionStartupTest {

  private static final String SCHEMA = "startup_adoption";

  @DynamicPropertySource
  static void preReleaseDatabase(DynamicPropertyRegistry registry) throws Exception {
    String url = System.getenv("PRUDENCE_TEST_POSTGRES_URL");
    String user = System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_USER", "postgres");
    String password = System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_PASSWORD", "");
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      c.createStatement().execute("drop schema if exists " + SCHEMA + " cascade");
      c.createStatement().execute("create schema " + SCHEMA);
    }
    Flyway.configure()
        .dataSource(url, user, password)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .locations("filesystem:src/test/resources/db/pre-release")
        .load()
        .migrate();
    registry.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    registry.add("spring.datasource.username", () -> user);
    registry.add("spring.datasource.password", () -> password);
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  void prudenceStartsOnAPreReleaseDatabaseWithTheBaselineHistory() {
    assertEquals(
        List.of("1"),
        jdbc.queryForList(
            "select version from " + SCHEMA + ".flyway_schema_history order by installed_rank",
            String.class));
  }
}
