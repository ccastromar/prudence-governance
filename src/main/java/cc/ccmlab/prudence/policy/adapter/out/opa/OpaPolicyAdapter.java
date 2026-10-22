package cc.ccmlab.prudence.policy.adapter.out.opa;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.application.bundle.PolicyPublicationService;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
  private final PolicyPublicationService publications;
  private final boolean managed;
  private final String instanceId;

  public OpaPolicyAdapter(
      IntegrationProperties props,
      PolicyPublicationService publications,
      @Value("${prudence.managed.enabled:false}") boolean managed,
      @Value("${prudence.managed.opa-token:}") String opaToken,
      @Value("${prudence.managed.opa-instance-id:}") String instanceId) {
    if (managed && (instanceId.isBlank() || instanceId.length() > 255))
      throw new IllegalStateException("managed policy requires a distinct OPA instance ID");
    var timeouts = new SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(java.time.Duration.ofSeconds(2));
    timeouts.setReadTimeout(java.time.Duration.ofSeconds(4));
    var builder = RestClient.builder().baseUrl(props.opa().baseUrl()).requestFactory(timeouts);
    if (!opaToken.isBlank()) builder.defaultHeader("Authorization", "Bearer " + opaToken);
    this.client = builder.build();
    this.decisionPath = props.opa().decisionPath();
    this.publications = publications;
    this.managed = managed;
    this.instanceId = instanceId;
  }

  @Override
  public boolean isAllowed(DecisionContext context) {
    if (managed) return evaluate(context).outcome() == Outcome.ALLOW;
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

  @Override
  public Evaluation evaluate(DecisionContext context) {
    if (!managed) return PolicyEnginePort.super.evaluate(context);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken)
        || !authentication.isAuthenticated()
        || !authentication.getName().equals(context.subject()))
      return new Evaluation(Outcome.ERROR, null, null);
    if (!"run".equals(context.action()) && !"pipeline.run".equals(context.action()))
      return new Evaluation(Outcome.DENY, null, null);
    var roles =
        authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList();
    try {
      var required = publications.requiredForDecision();
      if (required == null || required.id() == null)
        return new Evaluation(Outcome.ERROR, null, null);
      Map<?, ?> body =
          client
              .post()
              .uri("/v1/data/prudence_managed/result")
              .body(
                  Map.of(
                      "input",
                      Map.of(
                          "roles",
                          roles,
                          "action",
                          "pipeline.run",
                          "pipeline",
                          context.pipeline())))
              .retrieve()
              .body(Map.class);
      var result = ManagedOpaResult.verify(body, required.id(), required.digest());
      if (result.outcome() != ManagedOpaResult.Outcome.ERROR)
        publications.observe(instanceId, result.version(), result.digest());
      return switch (result.outcome()) {
        case ALLOW -> new Evaluation(Outcome.ALLOW, result.version(), result.digest());
        case DENY -> new Evaluation(Outcome.DENY, result.version(), result.digest());
        case ERROR -> new Evaluation(Outcome.ERROR, null, null);
      };
    } catch (Exception e) {
      log.warn("Managed OPA evaluation failed: {}", e.getMessage());
      return new Evaluation(Outcome.ERROR, null, null);
    }
  }
}
