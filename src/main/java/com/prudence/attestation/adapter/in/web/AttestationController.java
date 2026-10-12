package com.prudence.attestation.adapter.in.web;

import com.prudence.attestation.application.port.in.AttestationUseCase;
import com.prudence.attestation.domain.Attestation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
