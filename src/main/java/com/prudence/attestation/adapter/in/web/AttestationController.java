package com.prudence.attestation.adapter.in.web;

import com.prudence.attestation.application.port.in.AttestationUseCase;
import com.prudence.attestation.domain.Attestation;
import com.prudence.attestation.domain.Verification;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/// Driving adapter: issue and read estate attestations. POST generates and seals a new one;
/// GET lists prior ones and fetches a full stored report.
@RestController
@RequestMapping("/api/attestations")
public class AttestationController {

  private final AttestationUseCase attestations;

  public AttestationController(AttestationUseCase attestations) {
    this.attestations = attestations;
  }

  @PostMapping
  public ResponseEntity<Attestation> generate() {
    return ResponseEntity.status(HttpStatus.CREATED).body(attestations.generate());
  }

  @GetMapping
  public List<AttestationUseCase.Summary> list() {
    return attestations.list();
  }

  @GetMapping("/{id}")
  public ResponseEntity<Attestation> get(@PathVariable String id) {
    return attestations.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }

  /// Independently verify a stored attestation: recompute its hash and confirm the seal
  /// against Ernest. The verdict proves whether the report is intact and provably sealed.
  @GetMapping("/{id}/verify")
  public ResponseEntity<Verification> verify(@PathVariable String id) {
    return attestations
        .verify(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /// The same attestation rendered as a NIST OSCAL Assessment Results document —
  /// machine-readable compliance that points back at the Ernest seal.
  @GetMapping("/{id}/oscal")
  public ResponseEntity<Map<String, Object>> oscal(@PathVariable String id) {
    return attestations
        .get(id)
        .map(OscalMapper::toAssessmentResults)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
