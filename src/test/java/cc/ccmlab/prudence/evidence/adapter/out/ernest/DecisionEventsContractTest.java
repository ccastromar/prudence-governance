package cc.ccmlab.prudence.evidence.adapter.out.ernest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/// Prudence's side of the decision events contract (contract/decision-events): every decision
/// becomes exactly the Kafka record (decision-transport=kafka) and the POST /events request
/// (decision-transport=ingestor) the vectors pin. Ernest's event-ingestor runs the same vectors
/// through its Kafka source and its HTTP handler. `-Dcontract.update=true` rewrites them.
class DecisionEventsContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path VECTORS = Path.of("contract/decision-events/vectors.json");

  @Test
  @SuppressWarnings("unchecked")
  void everyDecisionBecomesItsRecordAndItsRequest() throws Exception {
    Map<String, Object> doc = JSON.readValue(Files.readString(VECTORS), LinkedHashMap.class);
    boolean update = Boolean.getBoolean("contract.update");
    String ingestKey = (String) ((Map<String, Object>) doc.get("http")).get("ingestKey");
    for (var c : (List<Map<String, Object>>) doc.get("cases")) {
      var ctx = context((Map<String, Object>) c.get("context"));
      String id = (String) c.get("decisionId");
      Map<String, Object> record = kafkaRecord(id, ctx, c);
      Map<String, Object> request = httpRequest(id, ctx, decision(c), ingestKey);
      if (update) {
        c.put("record", record);
        c.put("http", request);
        continue;
      }
      check(c, "record", record);
      check(c, "http", request);
    }
    if (update) {
      Files.writeString(
          VECTORS,
          JSON.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(doc) + "\n");
    }
  }

  private static void check(Map<String, Object> c, String what, Map<String, Object> actual) {
    assertEquals(
        JSON.writeValueAsString(c.get(what)),
        JSON.writeValueAsString(actual),
        c.get("name")
            + ": the "
            + what
            + " changed. A change Ernest must see is a new contract version (README.md);"
            + " otherwise run with -Dcontract.update=true");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> kafkaRecord(
      String id, DecisionContext ctx, Map<String, Object> c) throws Exception {
    var producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    var outcomes =
        new ErnestKafkaPublisher(producer, "prudence.decisions")
            .send(
                List.of(
                    ErnestEvidenceAdapter.kafkaEvent(
                        id, ctx, (String) c.get("verdict"), (String) c.get("reason"))));
    assertNull(outcomes.getFirst());
    var sent = producer.history().getFirst();
    Map<String, Object> headers = new LinkedHashMap<>();
    sent.headers()
        .forEach(h -> headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("key", sent.key());
    record.put("headers", headers);
    record.put("value", JSON.readValue(sent.value(), LinkedHashMap.class));
    return record;
  }

  /// The real adapter with decision-transport=ingestor, against a stub ingestor that keeps the
  /// request and answers 202 like the real one. Header names are lower-cased: HTTP's are not
  /// case-sensitive.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> httpRequest(
      String id, DecisionContext ctx, PolicyDecision decision, String ingestKey) throws Exception {
    AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Map<String, Object> headers = new TreeMap<>();
          exchange
              .getRequestHeaders()
              .forEach(
                  (name, values) -> {
                    if (name.toLowerCase().startsWith("x-ernest-")
                        || name.equalsIgnoreCase("x-correlation-id"))
                      headers.put(name.toLowerCase(), values.getFirst());
                  });
          Map<String, Object> request = new LinkedHashMap<>();
          request.put("method", exchange.getRequestMethod());
          request.put("path", exchange.getRequestURI().getRawPath());
          request.put("headers", headers);
          request.put(
              "body",
              JSON.readValue(exchange.getRequestBody().readAllBytes(), LinkedHashMap.class));
          seen.set(request);
          byte[] answer = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(202, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var props =
          new IntegrationProperties(
              new IntegrationProperties.Opa("http://127.0.0.1:1", "/", "lab", "hash"),
              new IntegrationProperties.Ernest(
                  url,
                  null,
                  "write-key",
                  "ingestor",
                  url,
                  ingestKey,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              new IntegrationProperties.Qtsp(""));
      new ErnestEvidenceAdapter(props, ObservationRegistry.NOOP).publishDecision(id, ctx, decision);
    } finally {
      server.stop(0);
    }
    return seen.get();
  }

  private static PolicyDecision decision(Map<String, Object> c) {
    String reason = (String) c.get("reason");
    return switch ((String) c.get("verdict")) {
      case "ALLOW" -> new PolicyDecision.Allow();
      case "DENY" -> new PolicyDecision.Deny(reason);
      case "APPROVAL_REQUIRED" -> new PolicyDecision.ApprovalRequired("risk-officer", reason);
      case "ERROR" -> new PolicyDecision.Error(reason);
      default -> throw new IllegalArgumentException("unknown verdict " + c.get("verdict"));
    };
  }

  private static DecisionContext context(Map<String, Object> x) {
    return new DecisionContext(
        (String) x.get("subject"),
        (String) x.get("pipeline"),
        (String) x.get("aiSystemId"),
        (String) x.get("action"),
        (String) x.get("policyVersion"),
        (String) x.get("policyHash"),
        (String) x.get("systemVersion"),
        (String) x.get("modelHash"),
        (String) x.get("causationId"),
        (String) x.get("correlationId"),
        x.get("systemSequence") instanceof Number n ? n.longValue() : null,
        (String) x.get("aiSystemName"),
        (String) x.get("riskTier"),
        (String) x.get("decisionId"));
  }
}
