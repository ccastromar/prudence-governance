package cc.ccmlab.prudence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChestertownLogFormatTest {

  private final ChestertownLogFormat format =
      new ChestertownLogFormat("prudence", "0.1.0", "staging");
  private final Logger logger = new LoggerContext().getLogger("cc.ccmlab.prudence.Test");

  @Test
  void aLineCarriesTheSuitesFieldsTheTraceAndTheFlow() {
    LoggingEvent event =
        new LoggingEvent(
            "fqcn", logger, Level.WARN, "decision {} recorded", null, new Object[] {"d-1"});
    event.setMDCPropertyMap(
        Map.of(
            "traceId", "0af7651916cd43dd8448eb211c80319c",
            "spanId", "b7ad6b7169203331",
            "correlation_id", "task-7",
            "user", "someone@example.com"));
    event.setKeyValuePairs(
        List.of(
            new KeyValuePair("event.name", "policy.decision"),
            new KeyValuePair("outcome", "success"),
            new KeyValuePair("severity", "INFO")));

    JsonNode line = JsonMapper.builder().build().readTree(format.format(event));

    assertTrue(
        line.get("timestamp")
            .asString()
            .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
    assertEquals("WARN", line.get("severity").asString(), "a pair cannot overwrite a field");
    assertEquals("prudence", line.get("service.name").asString());
    assertEquals("0.1.0", line.get("service.version").asString());
    assertEquals("staging", line.get("deployment.environment.name").asString());
    assertEquals("decision d-1 recorded", line.get("message").asString());
    assertEquals("0af7651916cd43dd8448eb211c80319c", line.get("trace_id").asString());
    assertEquals("b7ad6b7169203331", line.get("span_id").asString());
    assertEquals("task-7", line.get("correlation_id").asString());
    assertEquals("policy.decision", line.get("event.name").asString());
    assertEquals("success", line.get("outcome").asString());
    assertFalse(line.has("user"), "other MDC keys are not written");
  }

  @Test
  void anExceptionGivesItsTypeAndFramesButNotItsMessage() {
    LoggingEvent event =
        new LoggingEvent(
            "fqcn",
            logger,
            Level.ERROR,
            "delivery failed",
            new IllegalStateException(
                "subject alice@example.com refused", new RuntimeException("SECRET")),
            null);
    event.setMDCPropertyMap(Map.of());

    String json = format.format(event);
    JsonNode line = JsonMapper.builder().build().readTree(json);

    assertEquals("java.lang.IllegalStateException", line.get("error.type").asString());
    assertTrue(
        line.get("exception.stacktrace")
            .asString()
            .contains("Caused by: java.lang.RuntimeException"));
    assertFalse(json.contains("alice@example.com"));
    assertFalse(json.contains("SECRET"));
    assertTrue(json.endsWith("\n") && json.indexOf('\n') == json.length() - 1, "one line");
  }
}
