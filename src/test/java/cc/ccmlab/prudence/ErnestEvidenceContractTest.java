package cc.ccmlab.prudence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.attestation.adapter.out.ernest.ErnestAttestationAdapter;
import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestEvidenceAdapter;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestHttp;
import cc.ccmlab.prudence.evidence.delivery.DecisionPublisher;
import cc.ccmlab.prudence.evidence.delivery.DeliveryFailure;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import cc.ccmlab.prudence.posture.adapter.out.ernest.ErnestEvidenceHealthAdapter;
import cc.ccmlab.prudence.registry.adapter.out.ernest.ReviewSealAdapter;
import cc.ccmlab.prudence.registry.domain.ReviewEvent;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/// Prudence's side of its contract with Ernest (contract/ernest/interactions.json): the real
/// adapters, against a stub that answers each interaction in order with its example response,
/// must send requests of the declared shape and read the answers as the interaction intends.
/// Ernest replays the same interactions against itself (backend/test/prudence-contract.test.js).
class ErnestEvidenceContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "contract-key";

  private HttpServer server;
  private List<Map<String, Object>> script;
  private int next;
  private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
  private IntegrationProperties props;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::answer);
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    props =
        new IntegrationProperties(
            new IntegrationProperties.Opa("http://127.0.0.1:1", "/", "lab", "hash"),
            new IntegrationProperties.Ernest(url, KEY),
            new IntegrationProperties.Qtsp(""));
  }

  /// The interactions of one file that Prudence sends (setup steps of other runtimes left out).
  @SuppressWarnings("unchecked")
  private void play(String file) throws IOException {
    Map<String, Object> contract =
        JSON.readValue(Files.readString(Path.of("contract/ernest", file)), Map.class);
    script =
        ((List<Map<String, Object>>) contract.get("interactions"))
            .stream().filter(i -> i.get("consumer") != null).toList();
    next = 0;
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void withBatchingAsDeployed() throws IOException {
    play("interactions.json");
    var observations = ObservationRegistry.NOOP;

    // Governance reviews: registered once, recorded, retried, conflicting, and from a fresh
    // instance that finds the register already there.
    var reviews = new ReviewSealAdapter(props, observations);
    var review = review("rev-1", null);
    // With batching (as deployed) Ernest accepts a record before chaining it: no block yet.
    assertNull(reviews.seal(review).blockIndex());
    reviews.seal(review);
    var conflict =
        assertThrows(
            RestClientResponseException.class, () -> reviews.seal(review("rev-1", "changed")));
    assertEquals(DeliveryFailure.PERMANENT, ErnestHttp.classify(conflict));
    assertNull(new ReviewSealAdapter(props, observations).seal(review("rev-2", null)).blockIndex());

    // PDP decisions, in one batch.
    var decisions = new ErnestEvidenceAdapter(props, observations);
    var outcomes =
        decisions.publishDecisions(
            List.of(
                new DecisionPublisher.Item("dec-1", decision("dec-1"), new PolicyDecision.Allow()),
                new DecisionPublisher.Item(
                    "dec-2", decision("dec-2"), new PolicyDecision.Deny("prohibited AI system"))));
    assertEquals(2, outcomes.size());
    outcomes.forEach(e -> assertNull(e, "every decision delivered"));

    // An attestation: sealed, then confirmed through its own inclusion proof.
    var attestations = new ErnestAttestationAdapter(props, observations);
    String hash = "a".repeat(64);
    assertNull(attestations.seal("att-1", hash, Map.of("overallPosture", "GREEN")).blockIndex());
    var confirmation = attestations.confirm("prudence-attestations", "att-1", hash);
    assertTrue(
        confirmation.reachable() && confirmation.present() && confirmation.chainValid(),
        "attestation confirmed: " + confirmation);
    assertEquals(1L, confirmation.blockIndex());

    // Posture: chain health, which systems have evidence, and executions per system.
    var health = new ErnestEvidenceHealthAdapter(props, observations);
    assertTrue(health.check().healthy());
    assertEquals(
        Set.of("sys-credit"), health.systemsWithEvidence(List.of("sys-credit", "sys-unused")));
    assertTrue(health.hasEvidence("sys-credit"));
    var scan = health.scan();
    assertTrue(scan.complete());
    assertEquals(Map.of("sys-credit", 1L), scan.counts());

    assertEquals(List.of(), List.copyOf(problems));
    assertEquals(script.size(), next, "every interaction was used");
  }

  /// Ernest's default (EVIDENCE_BATCHING=events): every record through the event store, chained in
  /// its own block before the write answers; retries are duplicates, conflicts are refused.
  @Test
  void withEventsTheDefault() throws IOException {
    play("interactions-events.json");
    var observations = ObservationRegistry.NOOP;

    var reviews = new ReviewSealAdapter(props, observations);
    var review = review("rev-1", null);
    assertEquals(2L, reviews.seal(review).blockIndex());
    assertEquals(2L, reviews.seal(review).blockIndex(), "a retry is the same record");
    var conflict =
        assertThrows(
            RestClientResponseException.class, () -> reviews.seal(review("rev-1", "changed")));
    assertEquals(DeliveryFailure.PERMANENT, ErnestHttp.classify(conflict));
    assertEquals(
        3L, new ReviewSealAdapter(props, observations).seal(review("rev-2", null)).blockIndex());

    var outcomes =
        new ErnestEvidenceAdapter(props, observations)
            .publishDecisions(
                List.of(
                    new DecisionPublisher.Item(
                        "dec-1", decision("dec-1"), new PolicyDecision.Allow()),
                    new DecisionPublisher.Item(
                        "dec-2",
                        decision("dec-2"),
                        new PolicyDecision.Deny("prohibited AI system"))));
    outcomes.forEach(e -> assertNull(e, "every decision delivered"));

    var attestations = new ErnestAttestationAdapter(props, observations);
    String hash = "a".repeat(64);
    assertEquals(
        8L, attestations.seal("att-1", hash, Map.of("overallPosture", "GREEN")).blockIndex());
    var confirmation = attestations.confirm("prudence-attestations", "att-1", hash);
    assertTrue(
        confirmation.reachable() && confirmation.present() && confirmation.chainValid(),
        "attestation confirmed through its block proof: " + confirmation);
    assertEquals(8L, confirmation.blockIndex());

    var health = new ErnestEvidenceHealthAdapter(props, observations);
    assertTrue(health.check().healthy());
    assertEquals(
        Set.of("sys-credit"), health.systemsWithEvidence(List.of("sys-credit", "sys-unused")));
    assertTrue(health.hasEvidence("sys-credit"));
    var scan = health.scan();
    assertTrue(scan.complete());
    assertEquals(Map.of("sys-credit", 1L), scan.counts());

    assertEquals(List.of(), List.copyOf(problems));
    assertEquals(script.size(), next, "every interaction was used");
  }

  /// Batching turned off on purpose: blocks at once, no event proofs (attestations are
  /// confirmed through the model's provenance), and no retry or conflict interactions, since
  /// Ernest does not deduplicate in this mode.
  @Test
  void withBatchingOff() throws IOException {
    play("interactions-unbatched.json");
    var observations = ObservationRegistry.NOOP;

    var reviews = new ReviewSealAdapter(props, observations);
    assertEquals(2L, reviews.seal(review("rev-1", null)).blockIndex());
    assertEquals(
        3L, new ReviewSealAdapter(props, observations).seal(review("rev-2", null)).blockIndex());

    var outcomes =
        new ErnestEvidenceAdapter(props, observations)
            .publishDecisions(
                List.of(
                    new DecisionPublisher.Item(
                        "dec-1", decision("dec-1"), new PolicyDecision.Allow()),
                    new DecisionPublisher.Item(
                        "dec-2",
                        decision("dec-2"),
                        new PolicyDecision.Deny("prohibited AI system"))));
    outcomes.forEach(e -> assertNull(e, "every decision delivered"));

    var attestations = new ErnestAttestationAdapter(props, observations);
    String hash = "a".repeat(64);
    assertEquals(
        8L, attestations.seal("att-1", hash, Map.of("overallPosture", "GREEN")).blockIndex());
    var confirmation = attestations.confirm("prudence-attestations", "att-1", hash);
    assertTrue(
        confirmation.reachable() && confirmation.present() && confirmation.chainValid(),
        "attestation confirmed through provenance: " + confirmation);
    assertEquals(8L, confirmation.blockIndex());

    var health = new ErnestEvidenceHealthAdapter(props, observations);
    assertTrue(health.check().healthy());
    assertEquals(
        Set.of("sys-credit"), health.systemsWithEvidence(List.of("sys-credit", "sys-unused")));
    assertTrue(health.hasEvidence("sys-credit"));
    var scan = health.scan();
    assertTrue(scan.complete());
    assertEquals(Map.of("sys-credit", 1L), scan.counts());

    assertEquals(List.of(), List.copyOf(problems));
    assertEquals(script.size(), next, "every interaction was used");
  }

  @SuppressWarnings("unchecked")
  private void answer(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = exchange.getRequestURI().getRawPath();
    if (exchange.getRequestURI().getRawQuery() != null)
      path += "?" + exchange.getRequestURI().getRawQuery();
    String call = exchange.getRequestMethod() + " " + path;
    Map<String, Object> response = Map.of("status", 500);
    if (next >= script.size()) {
      problems.add(call + ": no interaction left");
    } else {
      var interaction = script.get(next++);
      var request = (Map<String, Object>) interaction.get("request");
      response = (Map<String, Object>) interaction.get("response");
      String id = (String) interaction.get("id");
      String expected = request.get("method") + " " + request.get("path");
      if (!expected.equals(call))
        problems.add(id + ": expected " + expected + ", Prudence sent " + call);
      if (!KEY.equals(exchange.getRequestHeaders().getFirst("X-Ernest-Api-Key")))
        problems.add(id + ": no X-Ernest-Api-Key");
      if (request.containsKey("body")) {
        Object sent = body.isBlank() ? null : JSON.readValue(body, Object.class);
        var diff = new ArrayList<String>();
        sameShape(request.get("body"), sent, "$", diff);
        if (!diff.isEmpty()) problems.add(id + ": " + diff + " — Prudence sent " + body);
      }
    }
    // Several statuses allowed: answer with the last, what Ernest says with batching on.
    Object declared = response.get("status");
    int status =
        ((Number) (declared instanceof List<?> l ? l.get(l.size() - 1) : declared)).intValue();
    Object example = response.get("body");
    String text =
        example != null
            ? JSON.writeValueAsString(example)
            : response.get("contains") != null
                ? JSON.writeValueAsString(
                    Map.of("statusCode", status, "message", response.get("contains")))
                : "{}";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /// Same keys at every level and the same JSON types; values may differ (ids, hashes, times).
  private static void sameShape(Object declared, Object sent, String path, List<String> diff) {
    if (declared instanceof Map<?, ?> d && sent instanceof Map<?, ?> s) {
      for (var k : d.keySet()) if (!s.containsKey(k)) diff.add(path + "." + k + " missing");
      for (var k : s.keySet()) {
        if (!d.containsKey(k)) diff.add(path + "." + k + " not declared");
        else sameShape(d.get(k), s.get(k), path + "." + k, diff);
      }
    } else if (declared instanceof List<?> d && sent instanceof List<?> s) {
      if (d.isEmpty() || s.isEmpty()) {
        if (d.isEmpty() != s.isEmpty()) diff.add(path + " empty on one side");
        return;
      }
      for (int i = 0; i < s.size(); i++)
        sameShape(d.get(Math.min(i, d.size() - 1)), s.get(i), path + "[" + i + "]", diff);
    } else if (!Objects.equals(kind(declared), kind(sent))) {
      diff.add(path + " is " + kind(sent) + ", declared " + kind(declared));
    }
  }

  private static String kind(Object v) {
    if (v == null) return "null";
    if (v instanceof Map) return "object";
    if (v instanceof List) return "array";
    if (v instanceof String) return "string";
    if (v instanceof Boolean) return "boolean";
    if (v instanceof Number) return "number";
    return v.getClass().getSimpleName();
  }

  private static ReviewEvent review(String id, String reason) {
    return new ReviewEvent(
        id,
        "sys-credit",
        "APPROVE",
        "carol",
        reason,
        "2026-10-26T08:00:00Z",
        "PENDING_REVIEW",
        "APPROVED",
        "PENDING",
        false,
        null);
  }

  private static DecisionContext decision(String id) {
    return new DecisionContext(
        "aos",
        "credit_prescreening",
        "sys-credit",
        "run",
        "lab",
        "sha256:policy",
        "1.0.0",
        "sha256:model",
        null,
        null,
        null,
        "Credit scoring",
        "HIGH",
        id);
  }
}
