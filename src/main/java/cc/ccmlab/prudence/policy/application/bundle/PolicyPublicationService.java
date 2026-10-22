package cc.ccmlab.prudence.policy.application.bundle;

import cc.ccmlab.prudence.policy.adapter.in.web.PolicyDraftController.Rule;
import cc.ccmlab.prudence.policy.adapter.out.audit.PolicyAudit;
import cc.ccmlab.prudence.policy.adapter.out.persistence.PolicyRevisionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Serializes publication against a single durable channel. A published bundle is not an ACK. */
@Service
public class PolicyPublicationService {
  private final JdbcTemplate jdbc;
  private final PolicyRevisionRepository revisions;
  private final PolicyBundleBuilder builder;
  private final ObjectMapper json;
  private final PolicyAudit audit;

  public record Publication(
      String id,
      String digest,
      String revisionsJson,
      String createdBy,
      OffsetDateTime createdAt,
      String rollbackOf) {}

  public record Artifact(String id, String digest, byte[] bundle) {}

  public record Identity(String id, String digest) {}

  public record Observation(
      String instanceId, String publicationId, String digest, OffsetDateTime observedAt) {}

  public PolicyPublicationService(
      JdbcTemplate jdbc,
      PolicyRevisionRepository revisions,
      PolicyBundleBuilder builder,
      ObjectMapper json,
      PolicyAudit audit) {
    this.jdbc = jdbc;
    this.revisions = revisions;
    this.builder = builder;
    this.json = json;
    this.audit = audit;
  }

  @Transactional
  public Publication publish(String revisionId, long expectedVersion, String actor) {
    String previous = lockChannel();
    var revision =
        revisions
            .findById(revisionId)
            .orElseThrow(() -> new IllegalArgumentException("revision not found"));
    if (!"APPROVED".equals(revision.getStatus()) || revision.getVersion() != expectedVersion)
      throw new IllegalStateException("revision must be approved and unchanged");
    Map<String, String> selected = previous == null ? new TreeMap<>() : readSelection(previous);
    selected.put(revision.getPolicyId(), revisionId);
    return save(selected, actor, null);
  }

  @Transactional
  public Publication rollback(String publicationId, String actor) {
    lockChannel();
    return save(readSelection(publicationId), actor, publicationId);
  }

  @Transactional(readOnly = true)
  public List<Publication> history() {
    return jdbc.query(
        "select id,digest,revisions_json,created_by,created_at,rollback_of from policy_publication order by created_at desc",
        (rs, n) ->
            new Publication(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, OffsetDateTime.class),
                rs.getString(6)));
  }

  @Transactional(readOnly = true)
  public Artifact current() {
    var result =
        jdbc.query(
            "select p.id,p.digest,p.bundle from policy_publication p join policy_publication_channel c on c.publication_id=p.id where c.id=1",
            (rs, n) -> new Artifact(rs.getString(1), rs.getString(2), rs.getBytes(3)));
    return result.isEmpty() ? null : result.getFirst();
  }

  /** Holds the channel lock until the decision and its evidence are committed. */
  @Transactional
  public Identity requiredForDecision() {
    var rows =
        jdbc.query(
            "select p.id,p.digest from policy_publication_channel c left join policy_publication p on p.id=c.publication_id where c.id=1 for share of c",
            (rs, n) -> new Identity(rs.getString(1), rs.getString(2)));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional
  public void observe(String instance, String id, String digest) {
    jdbc.update(
        "insert into policy_opa_observation (instance_id,publication_id,digest,observed_at) values (?,?,?,?) on conflict (instance_id) do update set publication_id=excluded.publication_id,digest=excluded.digest,observed_at=excluded.observed_at",
        instance,
        id,
        digest,
        OffsetDateTime.now());
  }

  @Transactional(readOnly = true)
  public List<Observation> observations() {
    return jdbc.query(
        "select instance_id,publication_id,digest,observed_at from policy_opa_observation order by instance_id",
        (rs, n) ->
            new Observation(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, OffsetDateTime.class)));
  }

  private String lockChannel() {
    return jdbc.queryForObject(
        "select publication_id from policy_publication_channel where id=1 for update",
        String.class);
  }

  private Map<String, String> readSelection(String id) {
    var rows =
        jdbc.query(
            "select revisions_json from policy_publication where id=?",
            (rs, n) -> rs.getString(1),
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("publication not found");
    Map<String, String> selection =
        json.readValue(
            rows.getFirst(),
            json.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    return new TreeMap<>(selection);
  }

  private Publication save(Map<String, String> selected, String actor, String rollbackOf) {
    List<Rule> rules = new ArrayList<>();
    for (var entry : selected.entrySet()) {
      var revision =
          revisions
              .findById(entry.getValue())
              .orElseThrow(() -> new IllegalStateException("snapshot revision missing"));
      if (!entry.getKey().equals(revision.getPolicyId()) || revision.getApprovedBy() == null)
        throw new IllegalStateException("snapshot contains an unapproved revision");
      List<Rule> items =
          json.readValue(
              revision.getRulesJson(),
              json.getTypeFactory().constructCollectionType(List.class, Rule.class));
      rules.addAll(items);
    }
    if (rules.isEmpty() || rules.size() > 100)
      throw new IllegalStateException("a publication must contain between 1 and 100 rules");
    String id = UUID.randomUUID().toString();
    var bundle = builder.build(id, rules);
    String selection = json.writeValueAsString(selected);
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        "insert into policy_publication (id,digest,bundle,revisions_json,created_by,created_at,rollback_of) values (?,?,?,?,?,?,?)",
        id,
        bundle.digest(),
        bundle.archive(),
        selection,
        actor,
        now,
        rollbackOf);
    jdbc.update("update policy_publication_channel set publication_id=? where id=1", id);
    audit.record(
        id,
        rollbackOf == null ? "PUBLISHED" : "ROLLED_BACK",
        actor,
        json.writeValueAsString(
            Map.of(
                "digest",
                bundle.digest(),
                "revisions",
                selected,
                "rollbackOf",
                rollbackOf == null ? "" : rollbackOf)));
    return new Publication(id, bundle.digest(), selection, actor, now, rollbackOf);
  }
}
