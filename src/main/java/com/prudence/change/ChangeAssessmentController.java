package com.prudence.change;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/governance/change-assessments")
public class ChangeAssessmentController {
  private final ChangeAssessmentService service;

  public ChangeAssessmentController(ChangeAssessmentService s) {
    service = s;
  }

  @PostMapping
  public ChangeAssessmentJpaEntity assess(@Valid @RequestBody Body b, Principal p) {
    return service.assess(
        new ChangeAssessmentService.Request(
            b.aiSystemId, b.proposedVersion, b.changedFields, b.evidenceUri, b.evidenceSha256),
        p == null ? "aos-gate" : p.getName());
  }

  public record Body(
      @NotBlank String aiSystemId,
      @NotBlank String proposedVersion,
      List<String> changedFields,
      @NotBlank String evidenceUri,
      @NotBlank String evidenceSha256) {}
}
