package cc.ccmlab.prudence.policy.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.policy.adapter.in.web.AuthZenController.Evaluation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthZenEvaluationTest {

  private static final Map<String, String> TOOLS = Map.of("send_email", "system-mail");

  private static Map<String, Object> request(Map<String, Object> resource) {
    return Map.of(
        "subject", Map.of("type", "agent", "id", "gateway"),
        "resource", resource,
        "action", Map.of("name", "run"));
  }

  @Test
  void pipelineResourceKeepsItsPipelineAndAiSystem() {
    Evaluation e =
        Evaluation.parse(
            request(
                Map.of(
                    "type", "pipeline",
                    "id", "credit-risk-scoring",
                    "properties", Map.of("aiSystemId", "system-1"))),
            Map.of(),
            TOOLS);

    assertEquals("credit-risk-scoring", e.pipeline());
    assertEquals("system-1", e.aiSystemId());
  }

  @Test
  void toolIsGovernedAsToolPipelineOfItsMappedAiSystem() {
    Evaluation e =
        Evaluation.parse(request(Map.of("type", "tool", "id", "send_email")), Map.of(), TOOLS);

    assertEquals("tool:send_email", e.pipeline());
    assertEquals("system-mail", e.aiSystemId());
    assertEquals("run", e.action());
  }

  @Test
  void aiSystemSentByTheGatewayWinsOverTheMapping() {
    Evaluation e =
        Evaluation.parse(
            request(
                Map.of(
                    "type", "tool",
                    "id", "send_email",
                    "properties", Map.of("aiSystemId", "system-other"))),
            Map.of(),
            TOOLS);

    assertEquals("system-other", e.aiSystemId());
  }

  @Test
  void unmappedToolIsRefusedRatherThanGuessed() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Evaluation.parse(
                    request(Map.of("type", "tool", "id", "delete_account")), Map.of(), TOOLS));

    assertTrue(refused.getMessage().contains("delete_account"));
  }

  @Test
  void unknownResourceTypeIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Evaluation.parse(request(Map.of("type", "account", "id", "42")), Map.of(), TOOLS));
  }
}
