package cc.ccmlab.prudence.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Before the first public release Prudence's schema grew through 31 migrations; release 0.1.0
/// ships them as one baseline, `V1__baseline.sql`, that builds exactly the same schema
/// (PostgresMigrationsTest compares the two catalogs). A database those 31 migrations built is
/// therefore already at the baseline, but its Flyway history says otherwise, and Flyway would
/// refuse to start on it.
///
/// This adopts such a database, and only such a database: when the history holds exactly the
/// 31 pre-release migrations, all successful, it is rewritten to what a fresh install of the
/// baseline records (the rows of V2..V31 removed, V1 given the baseline's checksum), in one
/// transaction, and logged. Any other history is left to Flyway's own validation, which fails
/// closed as before. Then Flyway migrates as usual.
@Configuration
public class PreReleaseAdoption {

  private static final Logger log = LoggerFactory.getLogger(PreReleaseAdoption.class);

  /// The history the pre-release migrations leave: version → description.
  static final Map<String, String> PRE_RELEASE =
      new TreeMap<>(
          Map.ofEntries(
              Map.entry("1", "baseline"),
              Map.entry("2", "evidence outbox dead letter"),
              Map.entry("3", "review attestation delivery"),
              Map.entry("4", "approval lifecycle"),
              Map.entry("5", "alerts"),
              Map.entry("6", "incident register"),
              Map.entry("7", "drift scan incomplete alert"),
              Map.entry("8", "outbox due index"),
              Map.entry("9", "compliance controls"),
              Map.entry("10", "governance cases"),
              Map.entry("11", "control evidence contracts"),
              Map.entry("12", "change assessments"),
              Map.entry("13", "monitoring signals"),
              Map.entry("14", "capa"),
              Map.entry("15", "human oversight"),
              Map.entry("16", "human overrides"),
              Map.entry("17", "fria"),
              Map.entry("18", "compliance snapshots"),
              Map.entry("19", "system event sequence"),
              Map.entry("20", "outbox causality"),
              Map.entry("21", "impact requirements"),
              Map.entry("22", "transparency notices"),
              Map.entry("23", "transparency requirement"),
              Map.entry("24", "decision system identity"),
              Map.entry("25", "execution reports"),
              Map.entry("26", "policy control plane"),
              Map.entry("27", "policy draft audit"),
              Map.entry("28", "policy publications"),
              Map.entry("29", "policy actor length"),
              Map.entry("30", "policy audit outbox"),
              Map.entry("31", "ai system declarers")));

  @Bean
  FlywayMigrationStrategy adoptPreReleaseDatabases() {
    return flyway -> {
      adopt(flyway);
      flyway.migrate();
    };
  }

  /// Rewrites a pre-release history to the baseline's; returns whether it did.
  static boolean adopt(Flyway flyway) {
    MigrationInfo[] applied = flyway.info().applied();
    Map<String, String> history = new TreeMap<>();
    for (MigrationInfo m : applied) {
      if (m.getVersion() == null) return false; // repeatable or baseline markers: not ours
      if (!m.getState().isApplied() || m.getState().isFailed()) return false;
      history.put(m.getVersion().getVersion(), m.getDescription());
    }
    if (!history.equals(PRE_RELEASE)) return false;

    // The checksum of the baseline as it is resolved locally, what a fresh install records.
    Integer baselineChecksum = null;
    for (MigrationInfo m : flyway.info().all()) {
      if (m.getVersion() != null && "1".equals(m.getVersion().getVersion())) {
        baselineChecksum = m.getResolvedChecksum();
      }
    }
    if (baselineChecksum == null) return false;

    var config = flyway.getConfiguration();
    String schema =
        config.getDefaultSchema() != null
            ? config.getDefaultSchema()
            : config.getSchemas().length > 0 ? config.getSchemas()[0] : null;
    String table = (schema != null ? quote(schema) + "." : "") + quote(config.getTable());
    try (Connection c = config.getDataSource().getConnection()) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try (var delete =
              c.prepareStatement(
                  "delete from " + table + " where version is not null and version <> '1'");
          var update =
              c.prepareStatement("update " + table + " set checksum = ? where version = '1'")) {
        int removed = delete.executeUpdate();
        update.setInt(1, baselineChecksum);
        update.executeUpdate();
        c.commit();
        log.warn(
            "Adopted a pre-release database: its schema is the 0.1.0 baseline, so the history of"
                + " the {} pre-release migrations was replaced by the baseline's (V1).",
            removed + 1);
        return true;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not adopt the pre-release database", e);
    }
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
