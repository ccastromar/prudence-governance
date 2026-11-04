package cc.ccmlab.prudence.policy.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void toolIsGovernedAsToolPipelineAndKeepsItsMappedAiSystemAside() {
    Evaluation e =
        Evaluation.parse(request(Map.of("type", "tool", "id", "send_email")), Map.of(), TOOLS);

    assertEquals("tool:send_email", e.pipeline());
    // The mapping (deprecated) serves only a caller asking for itself; the controller decides.
    assertNull(e.aiSystemId());
    assertEquals("system-mail", e.mappedSystem());
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
  void unmappedToolLeavesTheAiSystemToThePdpRatherThanGuessing() {
    // The PDP takes the one system the agent acts for, or denies (agent_unknown,
    // agent_system_ambiguous): contract/identity section 5.
    Evaluation e =
        Evaluation.parse(request(Map.of("type", "tool", "id", "delete_account")), Map.of(), TOOLS);

    assertEquals("tool:delete_account", e.pipeline());
    assertNull(e.aiSystemId());
    assertNull(e.mappedSystem());
  }

  @Test
  void modelIsGovernedAsModelPipeline() {
    Evaluation e =
        Evaluation.parse(request(Map.of("type", "model", "id", "gpt-x")), Map.of(), TOOLS);

    assertEquals("model:gpt-x", e.pipeline());
    assertNull(e.aiSystemId());
  }

  @Test
  void contextNamesTheAgentAndCarriesItsToken() {
    Map<String, Object> body =
        Map.of(
            "subject", Map.of("type", "user", "id", "alice"),
            "resource", Map.of("type", "tool", "id", "send_email"),
            "action", Map.of("name", "tools/call"),
            "context", Map.of("agent", Map.of("id", "claims-assistant", "token", "a.b.c")));

    Evaluation e = Evaluation.parse(body, Map.of(), TOOLS);

    assertEquals("user", e.subjectType());
    assertEquals("alice", e.subjectId());
    assertEquals("claims-assistant", e.agent());
    assertEquals("a.b.c", e.agentToken());
    assertNull(e.personToken());
  }

  @Test
  void unknownResourceTypeIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Evaluation.parse(request(Map.of("type", "account", "id", "42")), Map.of(), TOOLS));
  }
}
