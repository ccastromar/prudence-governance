package com.prudence.policy.adapter.out.opa;

import com.prudence.config.IntegrationProperties;
import com.prudence.policy.application.port.out.PolicyEnginePort;
import com.prudence.policy.domain.DecisionContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/// Driven adapter to Open Policy Agent. POSTs the decision input to OPA's data API and
/// reads `result`. Fail-closed: any error denies (a governance engine that is down must
/// never grant access).
@Component
public class OpaPolicyAdapter implements PolicyEnginePort {

  private static final Logger log = LoggerFactory.getLogger(OpaPolicyAdapter.class);

  private final RestClient client;
  private final String decisionPath;

  public OpaPolicyAdapter(IntegrationProperties props) {
    this.client = RestClient.builder().baseUrl(props.opa().baseUrl()).build();
    this.decisionPath = props.opa().decisionPath();
  }

  @Override
  public boolean isAllowed(DecisionContext context) {
    try {
      Map<?, ?> body =
          client
              .post()
              .uri(decisionPath)
              .body(
                  Map.of(
                      "input",
                      Map.of(
                          "subject", context.subject(),
                          "pipeline", context.pipeline(),
                          "aiSystemId", context.aiSystemId(),
                          "action", context.action())))
              .retrieve()
              .body(Map.class);
      return body != null && Boolean.TRUE.equals(body.get("result"));
    } catch (Exception e) {
      log.warn("OPA unreachable, failing closed (deny): {}", e.getMessage());
      return false;
    }
  }
}
