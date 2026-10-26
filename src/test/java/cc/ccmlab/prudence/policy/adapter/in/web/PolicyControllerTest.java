package cc.ccmlab.prudence.policy.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.adapter.in.web.dto.DecisionRequest;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

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

  private static PolicyController controller(DecidePolicyUseCase pdp) {
    return new PolicyController(
        pdp,
        new IntegrationProperties(
            new IntegrationProperties.Opa("http://opa", "/allow", "test-v1", "test-hash"),
            new IntegrationProperties.Ernest("http://ernest", "key"),
            new IntegrationProperties.Qtsp("")),
        new DecisionMetrics(new SimpleMeterRegistry()));
  }
}
