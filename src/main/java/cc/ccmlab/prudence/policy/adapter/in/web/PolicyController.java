package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.adapter.in.web.dto.DecisionRequest;
import cc.ccmlab.prudence.policy.adapter.in.web.dto.DecisionResponse;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/// The PDP endpoint a runtime gate calls (Decision API v1, `docs/api/decision-api-v1.yaml`).
/// Maps the sealed decision to HTTP with exhaustive pattern matching — allow 200, approval
/// 202, deny 403 — and returns the id under which the decision was recorded as evidence.
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

  private final DecidePolicyUseCase pdp;
  private final IntegrationProperties properties;

  public PolicyController(DecidePolicyUseCase pdp, IntegrationProperties properties) {
    this.pdp = pdp;
    this.properties = properties;
  }

  @PostMapping("/decisions")
  public ResponseEntity<DecisionResponse> decide(
      @Valid @RequestBody DecisionRequest request, Authentication authentication) {
    // In a secured deployment the authorization subject is always the authenticated
    // workload identity.  The request field is retained only for the open dev profile.
    // A caller must not be able to impersonate another principal by changing JSON.
    String subject = authentication != null ? authentication.getName() : request.subject();
    var recorded =
        RecordedDecisions.decide(
            pdp, properties, subject, request.pipeline(), request.aiSystemId(), request.action());
    String decisionId = recorded.decisionId();
    String policyVersion = recorded.policyVersion();
    PolicyDecision decision = recorded.decision();

    return switch (decision) {
      case PolicyDecision.Allow ignored ->
          ResponseEntity.ok(new DecisionResponse("ALLOW", null, null, decisionId, policyVersion));
      case PolicyDecision.ApprovalRequired ar ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(
                  new DecisionResponse(
                      "APPROVAL_REQUIRED", ar.reason(), ar.approver(), decisionId, policyVersion));
      case PolicyDecision.Deny d ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(new DecisionResponse("DENY", d.reason(), null, decisionId, policyVersion));
    };
  }
}
