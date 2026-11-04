package cc.ccmlab.prudence.policy.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/// `correlationId` (optional) names the caller's business flow, an AOS task for example: it is
/// recorded with the decision and travels with its evidence.
///
/// `agent`, `agentToken`, `onBehalfOf` and `onBehalfOfToken` (optional, `contract/identity/`):
/// the agent the caller asks for, if not itself, and the person it acts for.
public record DecisionRequest(
    @NotBlank String subject,
    @NotBlank String pipeline,
    @NotBlank String aiSystemId,
    @NotBlank String action,
    @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String correlationId,
    @Size(max = 255) String agent,
    String agentToken,
    @Size(max = 255) String onBehalfOf,
    String onBehalfOfToken) {

  public DecisionRequest(
      String subject, String pipeline, String aiSystemId, String action, String correlationId) {
    this(subject, pipeline, aiSystemId, action, correlationId, null, null, null, null);
  }

  public DecisionRequest(String subject, String pipeline, String aiSystemId, String action) {
    this(subject, pipeline, aiSystemId, action, null);
  }
}
