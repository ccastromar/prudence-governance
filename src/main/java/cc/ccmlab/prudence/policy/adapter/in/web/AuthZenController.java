package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.config.AuthZenProperties;
import cc.ccmlab.prudence.config.CorrelationIdFilter;
import cc.ccmlab.prudence.config.ForwardedTokens;
import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/// OpenID AuthZEN Authorization API 1.0 in front of Prudence's PDP (ADR-001), so any
/// orchestrator that speaks AuthZEN can be governed by Prudence without Prudence's own API.
///
/// Mapping: `resource` is `{type: "pipeline", id: <pipeline>, properties: {aiSystemId}}`, or,
/// for an MCP gateway asking about a tool call, `{type: "tool", id: <tool name>}`. A tool is
/// checked as the pipeline `tool:<name>` of the AI system it acts for: `properties.aiSystemId`
/// when the gateway sends it, otherwise the one system its agent acts for (the PDP denies an
/// agent with none or several); `prudence.authzen.tool-systems` still serves a caller asking for
/// itself. `action.name` is the action; the caller is the authenticated workload identity, as in
/// Decision API v1, and the agent is `context.agent.id` or the caller; a `subject` of type `user`
/// is the person, recorded as asserted (`contract/identity/`). Every evaluation
/// is a recorded decision. AuthZEN decisions are booleans: only ALLOW is `true`, so a generic
/// client fails closed on APPROVAL_REQUIRED; `context` carries Prudence's verdict, the reason,
/// who must approve, the decisionId and the policy version for clients that know Prudence.
@RestController
public class AuthZenController {

  static final String EVALUATION_PATH = "/access/v1/evaluation";
  static final String EVALUATIONS_PATH = "/access/v1/evaluations";
  private static final String REQUEST_ID = "X-Request-ID";

  private final DecidePolicyUseCase pdp;
  private final IntegrationProperties properties;
  private final Map<String, String> toolSystems;
  private final String publicUrl;
  private final DecisionMetrics metrics;
  private final ForwardedTokens tokens;

  public AuthZenController(
      DecidePolicyUseCase pdp,
      IntegrationProperties properties,
      AuthZenProperties authZen,
      @Value("${prudence.authzen.pdp-url:${PRUDENCE_CONSOLE_URL:}}") String publicUrl,
      DecisionMetrics metrics,
      ForwardedTokens tokens) {
    this.metrics = metrics;
    this.tokens = tokens;
    this.pdp = pdp;
    this.properties = properties;
    this.toolSystems = authZen.toolSystems();
    this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
  }

  /// PDP metadata (AuthZEN §discovery). `policy_decision_point` is this PDP's public base URL.
  @GetMapping("/.well-known/authzen-configuration")
  public Map<String, Object> configuration(HttpServletRequest request) {
    String base =
        publicUrl.isEmpty()
            ? ServletUriComponentsBuilder.fromContextPath(request).build().toUriString()
            : publicUrl;
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("policy_decision_point", base);
    metadata.put("access_evaluation_endpoint", base + EVALUATION_PATH);
    metadata.put("access_evaluations_endpoint", base + EVALUATIONS_PATH);
    return metadata;
  }

  @PostMapping(EVALUATION_PATH)
  public ResponseEntity<Map<String, Object>> evaluation(
      @RequestBody(required = false) Map<String, Object> body,
      @RequestHeader(value = REQUEST_ID, required = false) String requestId,
      Authentication authentication) {
    Evaluation e;
    DecisionIdentity identity;
    try {
      e = Evaluation.parse(body == null ? Map.of() : body, Map.of(), toolSystems);
      identity = identityOf(e, authentication);
    } catch (IllegalArgumentException invalid) {
      return withRequestId(ResponseEntity.status(HttpStatus.BAD_REQUEST), requestId)
          .body(Map.of("error", Map.of("status", 400, "message", invalid.getMessage())));
    }
    return withRequestId(ResponseEntity.ok(), requestId).body(decide(e, identity));
  }

  @PostMapping(EVALUATIONS_PATH)
  public ResponseEntity<Map<String, Object>> evaluations(
      @RequestBody(required = false) Map<String, Object> body,
      @RequestHeader(value = REQUEST_ID, required = false) String requestId,
      Authentication authentication) {
    Map<String, Object> request = body == null ? Map.of() : body;
    Object listed = request.get("evaluations");
    // Absent or empty `evaluations`: behaves as a single evaluation (AuthZEN).
    if (!(listed instanceof List<?> items) || items.isEmpty()) {
      return evaluation(request, requestId, authentication);
    }
    String semantic = semanticOf(request.get("options"));
    if (semantic == null) {
      return withRequestId(ResponseEntity.status(HttpStatus.BAD_REQUEST), requestId)
          .body(
              Map.of(
                  "error",
                  Map.of(
                      "status",
                      400,
                      "message",
                      "options.evaluations_semantic must be execute_all, deny_on_first_deny or"
                          + " permit_on_first_permit")));
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Object item : items) {
      Map<String, Object> result;
      try {
        Evaluation e = Evaluation.parse(asMap(item), request, toolSystems);
        result = decide(e, identityOf(e, authentication));
      } catch (IllegalArgumentException invalid) {
        // A per-evaluation error is a denial carrying the error (AuthZEN batch semantics).
        result = new LinkedHashMap<>();
        result.put("decision", false);
        result.put(
            "context", Map.of("error", Map.of("status", 400, "message", invalid.getMessage())));
      }
      results.add(result);
      boolean decision = Boolean.TRUE.equals(result.get("decision"));
      if ("deny_on_first_deny".equals(semantic) && !decision) break;
      if ("permit_on_first_permit".equals(semantic) && decision) break;
    }
    return withRequestId(ResponseEntity.ok(), requestId).body(Map.of("evaluations", results));
  }

  /// As in Decision API v1: the token, not the JSON, names the caller outside dev. The agent is
  /// `context.agent`, else the caller; a `subject` of type `user` is the person, as asserted.
  private DecisionIdentity identityOf(Evaluation e, Authentication authentication) {
    String caller;
    if (authentication != null) {
      caller = authentication.getName();
    } else {
      // Open dev profile only: the body names the caller, unless it names a person.
      caller = "user".equals(e.subjectType()) ? e.agent() : e.subjectId();
      if (caller == null || caller.isBlank()) {
        throw new IllegalArgumentException("context.agent.id is required for a user subject");
      }
    }
    return RecordedDecisions.identity(
        caller,
        e.agent(),
        "user".equals(e.subjectType()) ? e.subjectId() : null,
        e.agentToken(),
        e.personToken(),
        tokens);
  }

  private Map<String, Object> decide(Evaluation e, DecisionIdentity identity) {
    // A tool call without a system: the PDP takes the one system its agent acts for. The
    // configured tool mapping (deprecated) still serves a caller asking for itself.
    String aiSystemId =
        e.aiSystemId() != null || identity.delegated() ? e.aiSystemId() : e.mappedSystem();
    var recorded =
        metrics.time(
            "authzen",
            () ->
                RecordedDecisions.decide(
                    pdp,
                    properties,
                    identity,
                    e.pipeline(),
                    aiSystemId,
                    e.action(),
                    CorrelationIdFilter.current()));
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("decisionId", recorded.decisionId());
    context.put("policyVersion", recorded.policyVersion());
    context.put("policyDigest", recorded.policyDigest());
    boolean allowed;
    switch (recorded.decision()) {
      case PolicyDecision.Allow ignored -> {
        allowed = true;
        context.put("prudenceDecision", "ALLOW");
      }
      case PolicyDecision.ApprovalRequired a -> {
        allowed = false;
        context.put("prudenceDecision", "APPROVAL_REQUIRED");
        context.put("approvalRequired", true);
        context.put("approver", a.approver());
        context.put("reason", a.reason());
      }
      case PolicyDecision.Deny d -> {
        allowed = false;
        context.put("prudenceDecision", "DENY");
        context.put("reason", d.reason());
        if (d.reasonCode() != null) context.put("reasonCode", d.reasonCode());
      }
      case PolicyDecision.Error failure -> {
        allowed = false;
        context.put("prudenceDecision", "ERROR");
        context.put("reason", failure.reason());
      }
    }
    context.put("identity", RecordedDecisions.view(recorded.identity()));
    // The system it was recorded under, which a tool call may not have named: a gateway cites it
    // when it reports the execution.
    if (recorded.aiSystemId() != null) context.put("aiSystemId", recorded.aiSystemId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("decision", allowed);
    result.put("context", context);
    return result;
  }

  private static ResponseEntity.BodyBuilder withRequestId(
      ResponseEntity.BodyBuilder builder, String requestId) {
    if (requestId != null && !requestId.isBlank()) {
      builder.header(REQUEST_ID, requestId);
    }
    return builder.header(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  private static String semanticOf(Object options) {
    Object s = asMapOrEmpty(options).get("evaluations_semantic");
    if (s == null) return "execute_all";
    return switch (String.valueOf(s)) {
      case "execute_all", "deny_on_first_deny", "permit_on_first_permit" -> String.valueOf(s);
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    if (!(value instanceof Map<?, ?> m))
      throw new IllegalArgumentException("evaluation must be an object");
    return (Map<String, Object>) m;
  }

  private static Map<String, Object> asMapOrEmpty(Object value) {
    return value instanceof Map<?, ?> ? asMap(value) : Map.of();
  }

  /// One evaluation, with the batch's top-level subject/resource/action as defaults.
  /// `agent` is `context.agent.id` (null when absent); `agentToken` and `personToken` are
  /// `context.agent.token` and `context.onBehalfOfToken`, or null (`contract/identity/`).
  /// `aiSystemId` is null for a tool call that sent none; `mappedSystem` is the tool's system in
  /// `prudence.authzen.tool-systems` (deprecated), if any.
  record Evaluation(
      String subjectId,
      String subjectType,
      String pipeline,
      String aiSystemId,
      String action,
      String agent,
      String agentToken,
      String personToken,
      String mappedSystem) {

    static Evaluation parse(
        Map<String, Object> item, Map<String, Object> defaults, Map<String, String> toolSystems) {
      Map<String, Object> subject = pick(item, defaults, "subject");
      Map<String, Object> resource = pick(item, defaults, "resource");
      Map<String, Object> action = pick(item, defaults, "action");
      Map<String, Object> context =
          item.get("context") instanceof Map<?, ?>
              ? asMap(item.get("context"))
              : asMapOrEmpty(defaults.get("context"));
      Map<String, Object> agent = asMapOrEmpty(context.get("agent"));

      String subjectId = required(subject, "id", "subject.id");
      String subjectType = required(subject, "type", "subject.type");
      String resourceType = required(resource, "type", "resource.type");
      String resourceId = required(resource, "id", "resource.id");
      Map<String, Object> properties = asMapOrEmpty(resource.get("properties"));
      String pipeline;
      String aiSystemId;
      String mappedSystem = null;
      switch (resourceType) {
        case "pipeline" -> {
          pipeline = resourceId;
          aiSystemId = required(properties, "aiSystemId", "resource.properties.aiSystemId");
        }
        case "tool" -> {
          // An MCP tool call: governed as the pipeline `tool:<name>` of the AI system the tool
          // acts for: the one sent, else the one system its agent acts for. Never guessed.
          pipeline = "tool:" + resourceId;
          Object sent = properties.get("aiSystemId");
          aiSystemId = sent != null && !String.valueOf(sent).isBlank() ? String.valueOf(sent) : null;
          mappedSystem = toolSystems.get(resourceId);
        }
        case "model" -> {
          // A model call through an AI gateway: governed as the pipeline `model:<name>` of the
          // AI system the agent acts for, so a suspended or unapproved system cannot reach a
          // model either, and the policy says which models an agent may use.
          pipeline = "model:" + resourceId;
          Object sent = properties.get("aiSystemId");
          aiSystemId = sent != null && !String.valueOf(sent).isBlank() ? String.valueOf(sent) : null;
        }
        default ->
            throw new IllegalArgumentException(
                "resource.type must be \"pipeline\", \"tool\" or \"model\" (was \""
                    + resourceType
                    + "\")");
      }
      String actionName = required(action, "name", "action.name");
      Object agentId = agent.get("id");
      return new Evaluation(
          subjectId,
          subjectType,
          pipeline,
          aiSystemId,
          actionName,
          agentId == null || String.valueOf(agentId).isBlank() ? null : String.valueOf(agentId),
          textOrNull(agent.get("token")),
          textOrNull(context.get("onBehalfOfToken")),
          mappedSystem == null || mappedSystem.isBlank() ? null : mappedSystem);
    }

    private static String textOrNull(Object value) {
      return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static Map<String, Object> pick(
        Map<String, Object> item, Map<String, Object> defaults, String key) {
      Object own = item.get(key);
      if (own instanceof Map<?, ?>) return asMap(own);
      Object fallback = defaults.get(key);
      if (fallback instanceof Map<?, ?>) return asMap(fallback);
      throw new IllegalArgumentException(key + " is required");
    }

    private static String required(Map<String, Object> m, String key, String label) {
      Object v = m.get(key);
      if (v == null || String.valueOf(v).isBlank()) {
        throw new IllegalArgumentException(label + " is required");
      }
      return String.valueOf(v);
    }
  }
}
