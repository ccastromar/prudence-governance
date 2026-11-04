package cc.ccmlab.prudence.policy.adapter.out.opa;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.config.MetricBuckets;
import cc.ccmlab.prudence.policy.application.bundle.PolicyPublicationService;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
  private final String delegationPath;
  private final String personRequirementPath;
  private final PolicyPublicationService publications;
  private final boolean managed;
  private final String instanceId;
  private final MeterRegistry meters;
  private final Map<String, Set<String>> speaksFor;

  private static final Set<String> MANAGED_ACTIONS = Set.of("run", "pipeline.run", "tools/call", "invoke");

  public OpaPolicyAdapter(
      IntegrationProperties props,
      ObservationRegistry observations,
      MeterRegistry meters,
      PolicyPublicationService publications,
      @Value("${prudence.managed.enabled:false}") boolean managed,
      @Value("${prudence.managed.opa-token:}") String opaToken,
      @Value("${prudence.managed.opa-instance-id:}") String instanceId,
      @Value("${prudence.managed.opa-ca-file:}") String caFile,
      @Value("${prudence.identity.speaks-for:}") String speaksFor) {
    this.speaksFor = parseSpeaksFor(speaksFor);
    if (managed && (instanceId.isBlank() || instanceId.length() > 255))
      throw new IllegalStateException("managed policy requires a distinct OPA instance ID");
    var builder =
        RestClient.builder()
            .baseUrl(props.opa().baseUrl())
            .requestFactory(factory(caFile))
            .observationRegistry(observations);
    if (!opaToken.isBlank()) builder.defaultHeader("Authorization", "Bearer " + opaToken);
    this.client = builder.build();
    this.decisionPath = props.opa().decisionPath();
    // `/v1/data/prudence/allow` -> `/v1/data/prudence/may_speak_for`: same package.
    this.delegationPath = decisionPath.replaceAll("/[^/]+$", "/may_speak_for");
    this.personRequirementPath = decisionPath.replaceAll("/[^/]+$", "/person_function_missing");
    this.publications = publications;
    this.meters = meters;
    this.managed = managed;
    this.instanceId = instanceId;
  }

  /// `caller=agent,caller=agent2,…` (managed policy only); blank: no caller speaks for another.
  static Map<String, Set<String>> parseSpeaksFor(String configured) {
    Map<String, Set<String>> out = new java.util.HashMap<>();
    if (configured == null || configured.isBlank()) return Map.of();
    for (String pair : configured.split(",")) {
      String[] parts = pair.strip().split("=", -1);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
        throw new IllegalArgumentException("invalid prudence.identity.speaks-for entry: " + pair);
      out.computeIfAbsent(parts[0].strip(), k -> new java.util.HashSet<>()).add(parts[1].strip());
    }
    Map<String, Set<String>> frozen = new java.util.HashMap<>();
    out.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
    return Map.copyOf(frozen);
  }

  /// Short timeouts; with `caFile`, OPA's certificate is verified against that private CA
  /// (PEM) instead of the JVM defaults. Hostname verification stays on.
  private static ClientHttpRequestFactory factory(String caFile) {
    if (caFile == null || caFile.isBlank()) {
      var simple = new SimpleClientHttpRequestFactory();
      simple.setConnectTimeout(Duration.ofSeconds(2));
      simple.setReadTimeout(Duration.ofSeconds(4));
      return simple;
    }
    try (var in = Files.newInputStream(Path.of(caFile))) {
      var store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in))
        store.setCertificateEntry("opa-ca-" + i++, cert);
      if (i == 0) throw new IllegalStateException("no certificate in " + caFile);
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      var ssl = SSLContext.getInstance("TLS");
      ssl.init(null, trust.getTrustManagers(), null);
      var http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(2));
      var jdk = new JdkClientHttpRequestFactory(http.build());
      jdk.setReadTimeout(Duration.ofSeconds(4));
      return jdk;
    } catch (Exception e) {
      throw new IllegalStateException("cannot load the OPA CA certificate", e);
    }
  }

  @Override
  public boolean isAllowed(DecisionContext context) {
    if (managed) return evaluate(context).outcome() == Outcome.ALLOW;
    try {
      Map<?, ?> body =
          client
              .post()
              .uri(decisionPath)
              .body(Map.of("input", input(context)))
              .retrieve()
              .body(Map.class);
      return body != null && Boolean.TRUE.equals(body.get("result"));
    } catch (Exception e) {
      log.warn("OPA unreachable, failing closed (deny): {}", e.getMessage());
      return false;
    }
  }

  /// The policy input (`contract/identity/` section 8): `subject` is the agent, so rules written
  /// against it keep their meaning; `caller`, `agentAssurance` and `onBehalfOf` say the rest.
  static Map<String, Object> input(DecisionContext context) {
    var identity = context.identity();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("subject", context.subject());
    input.put("caller", identity.caller());
    input.put("agent", identity.agent());
    input.put("agentAssurance", identity.agentAssurance().name().toLowerCase());
    if (identity.onBehalfOf() != null) {
      input.put(
          "onBehalfOf",
          Map.of(
              "id", identity.onBehalfOf().id(),
              "assurance", identity.onBehalfOf().assurance().name().toLowerCase(),
              "functions", identity.onBehalfOf().functions()));
    }
    input.put("pipeline", context.pipeline());
    input.put("aiSystemId", context.aiSystemId());
    input.put("action", context.action());
    return input;
  }

  /// Asks the policy whether `caller` may speak for `agent` (`data.prudence.speaks_for`). With
  /// managed policy, whose published rules are about roles and pipelines, the deployment's
  /// configuration says it (`prudence.identity.speaks-for`): which gateway stands in front of which
  /// agents is part of the deployment, reviewed with it.
  @Override
  public Delegation delegation(String caller, String agent) {
    if (managed)
      return speaksFor.getOrDefault(caller, Set.of()).contains(agent)
          ? Delegation.ALLOWED
          : Delegation.DENIED;
    try {
      Map<?, ?> body =
          client
              .post()
              .uri(delegationPath)
              .body(Map.of("input", Map.of("caller", caller, "agent", agent)))
              .retrieve()
              .body(Map.class);
      if (body == null) return Delegation.ERROR;
      return Boolean.TRUE.equals(body.get("result")) ? Delegation.ALLOWED : Delegation.DENIED;
    } catch (Exception e) {
      log.warn("OPA unreachable for a delegation check, failing closed: {}", e.getMessage());
      return Delegation.ERROR;
    }
  }

  /// Asked only after OPA denied, to name the denial; any failure leaves it unnamed (still DENY).
  @Override
  public boolean personFunctionMissing(DecisionContext context) {
    if (managed) return false;
    try {
      Map<?, ?> body =
          client
              .post()
              .uri(personRequirementPath)
              .body(Map.of("input", input(context)))
              .retrieve()
              .body(Map.class);
      return body != null && Boolean.TRUE.equals(body.get("result"));
    } catch (Exception e) {
      log.warn("OPA did not say why it denied: {}", e.getMessage());
      return false;
    }
  }

  /// `prudence.policy.evaluation.duration{outcome}` (the suite's observability conventions): the
  /// time OPA takes to evaluate, as a dependency of the decision. `failure` when it could not
  /// decide (unreachable, failing, or a managed check refused): the decision fails closed.
  @Override
  public Evaluation evaluate(DecisionContext context) {
    long started = System.nanoTime();
    String outcome = "failure";
    try {
      Evaluation evaluation = evaluateTimed(context);
      if (evaluation.outcome() != Outcome.ERROR) outcome = "success";
      return evaluation;
    } finally {
      Timer.builder("prudence.policy.evaluation.duration")
          .description("Time for the policy engine (OPA) to evaluate one decision")
          .tag("outcome", outcome)
          .serviceLevelObjectives(MetricBuckets.DECISION)
          .register(meters)
          .record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  private Evaluation evaluateTimed(DecisionContext context) {
    if (!managed) return PolicyEnginePort.super.evaluate(context);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var identity = context.identity();
    if (!(authentication instanceof JwtAuthenticationToken)
        || !authentication.isAuthenticated()
        || !authentication.getName().equals(identity.caller()))
      return new Evaluation(Outcome.ERROR, null, null);
    // Managed rules speak of running a pipeline; a gateway's tool call or model call is running
    // the pipeline `tool:<name>` or `model:<name>`.
    if (!MANAGED_ACTIONS.contains(context.action())) return new Evaluation(Outcome.DENY, null, null);
    List<String> roles;
    if (!identity.delegated()) {
      roles =
          authentication.getAuthorities().stream()
              .map(a -> a.getAuthority())
              .filter(a -> a.startsWith("ROLE_"))
              .map(a -> a.substring(5))
              .sorted()
              .toList();
    } else if (identity.agentAssurance() == DecisionIdentity.AgentAssurance.VERIFIED
        && identity.agentRoles() != null) {
      // The agent's own roles, from its own token: never the caller's, never the request's.
      roles = identity.agentRoles();
    } else {
      // A delegated agent's roles cannot be verified, so managed rules cannot grant it.
      return new Evaluation(Outcome.DENY, null, null);
    }
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

  /** What an operator-triggered check of the managed OPA instance found. */
  public record Probe(String state, String publicationId, String detail) {}

  /**
   * Evaluates a synthetic input and verifies the identity OPA returns against the publication the
   * channel requires. Only a verified match records an observation; delivery alone never does.
   */
  public Probe probe() {
    if (!managed) return new Probe("DISABLED", null, "managed policy is not enabled");
    try {
      var required = publications.requiredForDecision();
      if (required == null || required.id() == null)
        return new Probe("NOTHING_PUBLISHED", null, "no policy has been published yet");
      Map<?, ?> body =
          client
              .post()
              .uri("/v1/data/prudence_managed/result")
              .body(
                  Map.of(
                      "input",
                      Map.of(
                          "roles",
                          java.util.List.of("prudence-probe"),
                          "action",
                          "pipeline.run",
                          "pipeline",
                          "prudence-probe")))
              .retrieve()
              .body(Map.class);
      var result = ManagedOpaResult.verify(body, required.id(), required.digest());
      if (result.outcome() == ManagedOpaResult.Outcome.ERROR)
        return new Probe(
            "NOT_ACTIVE", required.id(), "OPA has not loaded the required publication yet");
      publications.observe(instanceId, result.version(), result.digest());
      return new Probe("ACTIVE", required.id(), "OPA evaluated the required publication");
    } catch (Exception e) {
      log.warn("Managed OPA probe failed: {}", e.getMessage());
      return new Probe("UNREACHABLE", null, "OPA could not be queried");
    }
  }
}
