package cc.ccmlab.prudence.evidence.adapter.out.ernest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class ErnestKafkaPublisherTest {

  @Test
  void publishesTheEventWithStableIdentityAndIngestorHeaders() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    ErnestKafkaPublisher publisher = new ErnestKafkaPublisher(producer, "prudence.decisions");

    var outcomes =
        publisher.send(
            java.util.List.of(
                new ErnestKafkaPublisher.Event(
                    "system-1",
                    "decision-1",
                    Map.of("modelId", "system-1", "inferenceId", "decision-1"))));

    assertEquals(1, outcomes.size());
    assertNull(outcomes.getFirst());
    var record = producer.history().getFirst();
    assertEquals("prudence.decisions", record.topic());
    assertEquals("system-1", record.key());
    assertEquals("prudence-pdp", header(record.headers().lastHeader("source")));
    assertEquals("policy.decision", header(record.headers().lastHeader("eventType")));
    assertEquals("decision-1", header(record.headers().lastHeader("sourceEventId")));
    assertTrue(
        new String(record.value(), StandardCharsets.UTF_8)
            .contains("\"inferenceId\":\"decision-1\""));
  }

  @Test
  void configuresSaslOverTlsWithEscapedCredentialsAndPrivateCa() {
    Map<String, Object> config =
        ErnestKafkaPublisher.securityConfig(
            "SASL_SSL",
            "SCRAM-SHA-512",
            "prudence",
            "se\"cret",
            "/etc/prudence/kafka-ca.pem",
            null,
            Map.of("ssl.endpoint.identification.algorithm", "https"));

    assertEquals("SASL_SSL", config.get("security.protocol"));
    assertEquals("SCRAM-SHA-512", config.get("sasl.mechanism"));
    assertEquals("PEM", config.get("ssl.truststore.type"));
    assertEquals("https", config.get("ssl.endpoint.identification.algorithm"));
    assertTrue(config.get("sasl.jaas.config").toString().contains("password=\"se\\\"cret\""));
  }

  @Test
  void refusesSaslWithoutBothCredentials() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                ErnestKafkaPublisher.securityConfig(
                    "SASL_SSL", "SCRAM-SHA-512", "prudence", "", null, null, Map.of()));

    assertTrue(failure.getMessage().contains("kafka-sasl-username"));
  }

  @Test
  void refusesUnknownSecurityProtocol() {
    assertThrows(
        IllegalStateException.class,
        () -> ErnestKafkaPublisher.securityConfig("TLS", "", "", "", null, null, Map.of()));
  }

  private static String header(Header header) {
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
