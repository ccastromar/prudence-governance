package cc.ccmlab.prudence.policy.adapter.out.opa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.application.bundle.PolicyPublicationService;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort.Delegation;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort.Outcome;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/// Managed policy behind an AI gateway (contract/identity): the deployment says which caller
/// speaks for which agent, a verified agent is decided by the roles in its own token, a merely
/// named one cannot be granted, and a tool or model call is running its pipeline.
class ManagedDelegationTest {

  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private final AtomicReference<String> sentToOpa = new AtomicReference<>();
  private HttpServer opa;
  private OpaPolicyAdapter adapter;

  @BeforeEach
  void start() throws Exception {
    opa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    opa.createContext(
        "/v1/data/prudence_managed/result",
        exchange -> {
          sentToOpa.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              ("{\"result\":{\"decision\":\"ALLOW\",\"version\":\"pub-1\",\"digest\":\"" + DIGEST + "\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    opa.start();
    PolicyPublicationService publications = mock(PolicyPublicationService.class);
    when(publications.requiredForDecision())
        .thenReturn(new PolicyPublicationService.Identity("pub-1", DIGEST));
    adapter =
        new OpaPolicyAdapter(
            new IntegrationProperties(
                new IntegrationProperties.Opa(
                    "http://127.0.0.1:" + opa.getAddress().getPort(), "/v1/data/prudence/allow", "v", "h"),
                new IntegrationProperties.Ernest("http://ernest", "key"),
                new IntegrationProperties.Qtsp("")),
            ObservationRegistry.NOOP,
            new SimpleMeterRegistry(),
            publications,
            true,
            "",
            "opa-1",
            "",
            "ai-gateway=demo-runner, ai-gateway=claims-assistant");
  }

  @AfterEach
  void stop() {
    opa.stop(0);
    SecurityContextHolder.clearContext();
  }

  private static void authenticatedAs(String name, String... roles) {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", name).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList(),
                name));
  }

  private static DecisionContext context(String action, DecisionIdentity identity) {
    return new DecisionContext(
        identity.agent(), "tool:approve_refund", "system-1", action, "v", "h", "1", "m", null, null,
        null, null, null, "d-1", identity);
  }

  @Test
  void theDeploymentSaysWhichCallerSpeaksForWhichAgent() {
    assertEquals(Delegation.ALLOWED, adapter.delegation("ai-gateway", "demo-runner"));
    assertEquals(Delegation.ALLOWED, adapter.delegation("ai-gateway", "claims-assistant"));
    assertEquals(Delegation.DENIED, adapter.delegation("ai-gateway", "payments-bot"));
    assertEquals(Delegation.DENIED, adapter.delegation("demo-runner", "ai-gateway"));
  }

  @Test
  void aVerifiedAgentIsDecidedByTheRolesInItsOwnToken() {
    authenticatedAs("ai-gateway", "gateway-role");
    var identity =
        new DecisionIdentity(
            "ai-gateway", "demo-runner", DecisionIdentity.AgentAssurance.VERIFIED, null, null,
            List.of("framework-demo-runner"));

    var evaluation = adapter.evaluate(context("tools/call", identity));

    assertEquals(Outcome.ALLOW, evaluation.outcome());
    var input = (java.util.Map<?, ?>) new tools.jackson.databind.ObjectMapper()
        .readValue(sentToOpa.get(), java.util.Map.class).get("input");
    assertEquals(List.of("framework-demo-runner"), input.get("roles"));
    assertEquals("pipeline.run", input.get("action"));
    assertEquals("tool:approve_refund", input.get("pipeline"));
  }

  @Test
  void aMerelyNamedAgentCannotBeGrantedByRoleRules() {
    authenticatedAs("ai-gateway", "framework-demo-runner");
    var identity =
        new DecisionIdentity("ai-gateway", "demo-runner", DecisionIdentity.AgentAssurance.DELEGATED, null);

    assertEquals(Outcome.DENY, adapter.evaluate(context("tools/call", identity)).outcome());
    assertNull(sentToOpa.get(), "OPA is not even asked");
  }

  @Test
  void theCallerMustBeTheAuthenticatedIdentity() {
    authenticatedAs("someone-else", "framework-demo-runner");
    var identity =
        new DecisionIdentity(
            "ai-gateway", "demo-runner", DecisionIdentity.AgentAssurance.VERIFIED, null, null,
            List.of("framework-demo-runner"));

    assertEquals(Outcome.ERROR, adapter.evaluate(context("invoke", identity)).outcome());
  }

  @Test
  void aCallerActingForItselfKeepsItsOwnRolesAndAnUnknownActionIsDenied() {
    authenticatedAs("demo-runner", "framework-demo-runner");
    var self = DecisionIdentity.self("demo-runner");

    assertEquals(Outcome.ALLOW, adapter.evaluate(context("run", self)).outcome());
    assertEquals(Outcome.DENY, adapter.evaluate(context("delete", self)).outcome());
  }

  @Test
  void aMalformedSpeaksForIsRefusedAtStart() {
    assertThrows(IllegalArgumentException.class, () -> OpaPolicyAdapter.parseSpeaksFor("ai-gateway"));
  }
}
