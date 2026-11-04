package cc.ccmlab.prudence.policy.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cc.ccmlab.prudence.config.ForwardedTokens;
import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.governance.GovernanceModel;
import cc.ccmlab.prudence.policy.adapter.in.web.dto.DecisionRequest;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

class PolicyControllerTest {

  @Test
  void usesAuthenticatedWorkloadIdentityInsteadOfCallerSuppliedSubject() {
    AtomicReference<DecisionContext> captured = new AtomicReference<>();
    DecidePolicyUseCase pdp =
        context -> {
          captured.set(context);
          return PolicyDecision.allow();
        };
    PolicyController controller = controller(pdp);

    controller.decide(
        new DecisionRequest("alice@bank.eu", "credit-risk-scoring", "system-1", "run"),
        UsernamePasswordAuthenticationToken.authenticated("workload-42", "n/a", List.of()));

    assertEquals("workload-42", captured.get().subject());
    assertEquals("credit-risk-scoring", captured.get().pipeline());
  }

  @Test
  void preservesRequestedSubjectOnlyInOpenDevelopmentMode() {
    AtomicReference<DecisionContext> captured = new AtomicReference<>();
    PolicyController controller =
        controller(
            context -> {
              captured.set(context);
              return PolicyDecision.allow();
            });

    controller.decide(
        new DecisionRequest("alice@bank.eu", "credit-risk-scoring", "system-1", "run"), null);

    assertEquals("alice@bank.eu", captured.get().subject());
  }

  @Test
  void anAgentNamedByTheCallerIsTheSubjectAndTheCallerIsKept() {
    AtomicReference<DecisionContext> captured = new AtomicReference<>();
    PolicyController controller =
        controller(
            context -> {
              captured.set(context);
              return PolicyDecision.allow();
            });

    var response =
        controller.decide(
            new DecisionRequest(
                "ignored",
                "tool:send_customer_letter",
                "system-1",
                "run",
                null,
                "claims-assistant",
                null,
                "alice",
                null),
            UsernamePasswordAuthenticationToken.authenticated("tool-gateway", "n/a", List.of()));

    var identity = captured.get().identity();
    assertEquals("claims-assistant", captured.get().subject());
    assertEquals("tool-gateway", identity.caller());
    assertEquals(DecisionIdentity.AgentAssurance.DELEGATED, identity.agentAssurance());
    assertEquals(DecisionIdentity.PersonAssurance.ASSERTED, identity.onBehalfOf().assurance());
    assertEquals("claims-assistant", response.getBody().identity().get("agent"));
  }

  @Test
  void aForwardedTokenWithoutAnIssuerIsABadRequest() {
    PolicyController controller = controller(context -> PolicyDecision.allow());

    var refused =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.decide(
                    new DecisionRequest(
                        "ignored", "p", "system-1", "run", null, null, "a.b.c", null, null),
                    UsernamePasswordAuthenticationToken.authenticated(
                        "tool-gateway", "n/a", List.of())));

    assertEquals(400, refused.getStatusCode().value());
  }

  private static PolicyController controller(DecidePolicyUseCase pdp) {
    var noBeans = new StaticListableBeanFactory();
    return new PolicyController(
        pdp,
        new IntegrationProperties(
            new IntegrationProperties.Opa("http://opa", "/allow", "test-v1", "test-hash"),
            new IntegrationProperties.Ernest("http://ernest", "key"),
            new IntegrationProperties.Qtsp("")),
        new DecisionMetrics(new SimpleMeterRegistry()),
        // No issuer: forwarded tokens cannot be validated.
        new ForwardedTokens(
            noBeans.getBeanProvider(JwtDecoder.class),
            "roles",
            "legacy",
            "",
            noBeans.getBeanProvider(GovernanceModel.class)));
  }
}
