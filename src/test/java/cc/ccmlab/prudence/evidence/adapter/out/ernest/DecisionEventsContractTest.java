package cc.ccmlab.prudence.evidence.adapter.out.ernest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/// Prudence's side of the decision events contract (contract/decision-events): every decision
/// becomes exactly the Kafka record the vectors pin. Ernest's event-ingestor runs the same
/// vectors through its Kafka source. `-Dcontract.update=true` rewrites the records.
class DecisionEventsContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path VECTORS = Path.of("contract/decision-events/vectors.json");

  @Test
  @SuppressWarnings("unchecked")
  void everyDecisionBecomesItsRecord() throws Exception {
    Map<String, Object> doc = JSON.readValue(Files.readString(VECTORS), LinkedHashMap.class);
    boolean update = Boolean.getBoolean("contract.update");
    for (var c : (List<Map<String, Object>>) doc.get("cases")) {
      var x = (Map<String, Object>) c.get("context");
      var ctx =
          new DecisionContext(
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
      var producer =
          new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
      var outcomes =
          new ErnestKafkaPublisher(producer, "prudence.decisions")
              .send(
                  List.of(
                      ErnestEvidenceAdapter.kafkaEvent(
                          (String) c.get("decisionId"),
                          ctx,
                          (String) c.get("verdict"),
                          (String) c.get("reason"))));
      assertNull(outcomes.getFirst());
      var sent = producer.history().getFirst();
      Map<String, Object> headers = new LinkedHashMap<>();
      sent.headers()
          .forEach(h -> headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("key", sent.key());
      record.put("headers", headers);
      record.put("value", JSON.readValue(sent.value(), LinkedHashMap.class));
      if (update) {
        c.put("record", record);
      } else {
        assertEquals(
            JSON.writeValueAsString(c.get("record")),
            JSON.writeValueAsString(record),
            c.get("name")
                + ": the record changed. A change Ernest must see is a new contract version"
                + " (README.md); otherwise run with -Dcontract.update=true");
      }
    }
    if (update) {
      Files.writeString(
          VECTORS,
          JSON.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(doc) + "\n");
    }
  }
}
