package com.prudence.policy.adapter.in.web;

import com.prudence.config.IntegrationProperties;
import com.prudence.policy.adapter.in.web.dto.DecisionRequest;
import com.prudence.policy.adapter.in.web.dto.DecisionResponse;
import com.prudence.policy.application.port.in.DecidePolicyUseCase;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/// The PDP endpoint an AOS gate calls. Maps the sealed decision to HTTP with exhaustive
/// pattern matching — allow 200, approval 202, deny 403.
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
    PolicyDecision decision =
        pdp.decide(
            new DecisionContext(
                subject,
                request.pipeline(),
                request.aiSystemId(),
                request.action(),
                properties.opa().policyVersion(),
                properties.opa().policyHash(),
                "unknown",
                "unknown"));

    return switch (decision) {
      case PolicyDecision.Allow ignored ->
          ResponseEntity.ok(new DecisionResponse("ALLOW", null, null));
      case PolicyDecision.ApprovalRequired ar ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(new DecisionResponse("APPROVAL_REQUIRED", ar.reason(), ar.approver()));
      case PolicyDecision.Deny d ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(new DecisionResponse("DENY", d.reason(), null));
    };
  }
}
