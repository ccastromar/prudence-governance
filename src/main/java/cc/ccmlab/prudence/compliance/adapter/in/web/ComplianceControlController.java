package cc.ccmlab.prudence.compliance.adapter.in.web;

import cc.ccmlab.prudence.compliance.adapter.in.web.dto.ComplianceControlResponse;
import cc.ccmlab.prudence.compliance.adapter.in.web.dto.SatisfyComplianceControlRequest;
import cc.ccmlab.prudence.compliance.application.port.in.ComplianceControls;
import cc.ccmlab.prudence.registry.application.port.in.AiSystemQueries;
import cc.ccmlab.prudence.registry.domain.AiSystemId;
import cc.ccmlab.prudence.registry.domain.AiSystemNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/// API for the governance evidence register. It deliberately accepts references/hashes rather
/// than files: documents stay in their system of record and Ernest can seal their hash.
@RestController
@RequestMapping("/api/registry/systems/{systemId}/compliance-controls")
public class ComplianceControlController {
  private final ComplianceControls controls;
  private final AiSystemQueries systems;

  public ComplianceControlController(ComplianceControls controls, AiSystemQueries systems) {
    this.controls = controls;
    this.systems = systems;
  }

  @GetMapping
  public List<ComplianceControlResponse> list(@PathVariable String systemId) {
    var system =
        systems
            .findById(new AiSystemId(systemId))
            .orElseThrow(() -> new AiSystemNotFoundException(new AiSystemId(systemId)));
    return controls.initializeAndList(system).stream()
        .map(
            control ->
                ComplianceControlResponse.from(control, system.classification().systemVersion()))
        .toList();
  }

  @PostMapping("/{controlId}/satisfy")
  public ComplianceControlResponse satisfy(
      @PathVariable String systemId,
      @PathVariable String controlId,
      @Valid @RequestBody SatisfyComplianceControlRequest request) {
    return ComplianceControlResponse.from(
        controls.satisfy(systemId, controlId, request.toEvidence()));
  }
}
