package cc.ccmlab.prudence.policy.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/// `correlationId` (optional) names the caller's business flow, an AOS task for example: it is
/// recorded with the decision and travels with its evidence (contract/observability, section 3).
public record DecisionRequest(
    @NotBlank String subject,
    @NotBlank String pipeline,
    @NotBlank String aiSystemId,
    @NotBlank String action,
    @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String correlationId) {

  public DecisionRequest(String subject, String pipeline, String aiSystemId, String action) {
    this(subject, pipeline, aiSystemId, action, null);
  }
}
