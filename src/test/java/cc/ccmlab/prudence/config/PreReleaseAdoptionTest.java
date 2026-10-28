package cc.ccmlab.prudence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/// The 0.1.0 baseline against the 31 pre-release migrations it replaces, on a real PostgreSQL
/// (PRUDENCE_TEST_POSTGRES_URL, as PostgresMigrationsTest): the same catalog, and a database the
/// pre-release migrations built is adopted with its data, while any other history still fails
/// closed. Each case works in a schema of its own.
@EnabledIfEnvironmentVariable(named = "PRUDENCE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PreReleaseAdoptionTest {

  private static final String URL = System.getenv("PRUDENCE_TEST_POSTGRES_URL");
  private static final String USER =
      System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_USER", "postgres");
  private static final String PASSWORD =
      System.getenv().getOrDefault("PRUDENCE_TEST_POSTGRES_PASSWORD", "");
  private static final String PRE_RELEASE = "filesystem:src/test/resources/db/pre-release";
  private static final String RELEASE = "classpath:db/migration";

  @Test
  void theBaselineBuildsExactlyTheSchemaOfThePreReleaseMigrations() throws SQLException {
    migrate(fresh("pre_release_schema"), PRE_RELEASE);
    configure(fresh("baseline_schema"), RELEASE).target("1").load().migrate();
    assertEquals(catalog("pre_release_schema"), catalog("baseline_schema"));
    assertEquals(
        rows("pre_release_schema", "select id, publication_id from policy_publication_channel"),
        rows("baseline_schema", "select id, publication_id from policy_publication_channel"));
  }

  @Test
  void aPreReleaseDatabaseIsAdoptedWithItsData() throws Exception {
    String schema = fresh("adopted");
    migrate(schema, PRE_RELEASE);
    sql(
        schema,
        "insert into ai_system (id, name, annexi, annexiii) values ('sys-1', 'Credit', false, true)");

    Flyway release = flyway(schema, RELEASE);
    assertThrows(Exception.class, release::validate, "Flyway alone refuses the old history");
    assertTrue(PreReleaseAdoption.adopt(release));
    release.migrate();
    release.validate();

    assertEquals(
        releaseVersions(),
        rows(schema, "select version from flyway_schema_history order by installed_rank"));
    assertEquals(List.of("Credit"), rows(schema, "select name from ai_system where id = 'sys-1'"));
    assertFalse(
        PreReleaseAdoption.adopt(flyway(schema, RELEASE)), "adopting twice changes nothing");
  }

  @Test
  void anyOtherHistoryIsLeftToFlywayAndFailsClosed() throws SQLException {
    String schema = fresh("not_ours");
    migrate(schema, PRE_RELEASE);
    sql(schema, "delete from flyway_schema_history where version = '31'");

    Flyway release = flyway(schema, RELEASE);
    assertFalse(PreReleaseAdoption.adopt(release));
    assertThrows(Exception.class, release::migrate);
  }

  private static Flyway flyway(String schema, String location) {
    return configure(schema, location).load();
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration configure(
      String schema, String location) {
    return Flyway.configure()
        .dataSource(URL, USER, PASSWORD)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(location);
  }

  private static void migrate(String schema, String location) {
    flyway(schema, location).migrate();
  }

  /// The history an adopted database ends with: the baseline (1), then every later release
  /// migration in order.
  static List<String> releaseVersions() throws IOException {
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
      return files
          .map(f -> f.getFileName().toString())
          .filter(n -> n.matches("V\\d+__.+\\.sql"))
          .map(n -> n.substring(1, n.indexOf("__")))
          .sorted(Comparator.comparingInt(Integer::parseInt))
          .toList();
    }
  }

  private static String fresh(String schema) throws SQLException {
    try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
      c.createStatement().execute("drop schema if exists " + schema + " cascade");
      c.createStatement().execute("create schema " + schema);
    }
    return schema;
  }

  private static void sql(String schema, String statement) throws SQLException {
    try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
      c.createStatement().execute("set search_path to " + schema);
      c.createStatement().execute(statement);
    }
  }

  private static List<String> rows(String schema, String query) throws SQLException {
    try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
      c.createStatement().execute("set search_path to " + schema);
      ResultSet rs = c.createStatement().executeQuery(query);
      List<String> out = new ArrayList<>();
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= n; i++) row.append(i > 1 ? "|" : "").append(rs.getString(i));
        out.add(row.toString());
      }
      return out;
    }
  }

  /// Tables, columns (in order, with type, nullability and default), constraints and indexes,
  /// by name and definition; Flyway's own history table aside.
  private static List<String> catalog(String schema) throws SQLException {
    List<String> out = new ArrayList<>();
    out.addAll(
        rows(
            schema,
            "select table_name, ordinal_position, column_name, data_type,"
                + " character_maximum_length, is_nullable, column_default"
                + " from information_schema.columns where table_schema = current_schema()"
                + " and table_name <> 'flyway_schema_history' order by 1, 2"));
    out.addAll(
        rows(
            schema,
            "select t.relname, c.conname, c.contype, pg_get_constraintdef(c.oid)"
                + " from pg_constraint c join pg_class t on t.oid = c.conrelid"
                + " join pg_namespace n on n.oid = t.relnamespace"
                + " where n.nspname = current_schema() and t.relname <> 'flyway_schema_history'"
                + " order by 1, 2"));
    out.addAll(
        rows(
            schema,
            "select tablename, indexname, replace(indexdef, current_schema() || '.', '')"
                + " from pg_indexes where schemaname = current_schema()"
                + " and tablename <> 'flyway_schema_history' order by 1, 2"));
    return out;
  }
}
