package com.prudence.attestation.application.service;

import tools.jackson.databind.json.JsonMapper;
import com.prudence.attestation.application.port.in.AttestationUseCase;
import com.prudence.attestation.application.port.out.AttestationRepository;
import com.prudence.attestation.application.port.out.AttestationSealPort;
import com.prudence.attestation.domain.Attestation;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import com.prudence.posture.application.port.in.PostureQuery;
import com.prudence.posture.domain.Posture;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// Builds a tamper-evident attestation of the estate and seals its hash in Ernest. Composes
/// the posture read model (what to report) with the evidence plane (how it's proved), so the
/// governance report is itself provable — ADR-000's closing step.
@Service
public class AttestationService implements AttestationUseCase {

    private final PostureQuery posture;
    private final AttestationSealPort sealPort;
    private final AttestationRepository repository;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AttestationService(PostureQuery posture, AttestationSealPort sealPort, AttestationRepository repository) {
        this.posture = posture;
        this.sealPort = sealPort;
        this.repository = repository;
    }

    @Override
    public Attestation generate() {
        Posture p = posture.compute();
        String id = UUID.randomUUID().toString();
        String generatedAt = Instant.now().toString();
        String overall = p.overall().name();

        List<Attestation.Entry> entries = p.systems().stream()
                .sorted(Comparator.comparing(Posture.SystemPosture::id))
                .map(s -> new Attestation.Entry(s.id(), s.name(), s.riskTier(),
                        s.approvalStatus(), s.status().name(), s.detail()))
                .toList();

        // Deterministic report (hashed + stored). Keys are canonicalized on hashing, so
        // insertion order here is only for the stored, human-readable JSON.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id);
        report.put("generatedAt", generatedAt);
        report.put("overallPosture", overall);
        report.put("systems", entries.stream().map(AttestationService::entryMap).toList());

        String contentHash = ErnestSealer.sha256Canonical(report);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", generatedAt);
        summary.put("overallPosture", overall);
        summary.put("systemCount", String.valueOf(entries.size()));

        Attestation.Evidence evidence = sealPort.seal(id, contentHash, summary);

        Attestation attestation = new Attestation(id, generatedAt, overall, contentHash, entries, evidence);
        repository.save(attestation, toJson(report));
        return attestation;
    }

    @Override
    public List<Summary> list() {
        return repository.findAllNewestFirst().stream()
                .map(s -> {
                    Attestation a = s.attestation();
                    return new Summary(a.id(), a.generatedAt(), a.overallPosture(),
                            a.contentHash(), a.evidence().sealed(), a.evidence().blockIndex());
                })
                .toList();
    }

    @Override
    public Optional<Attestation> get(String id) {
        return repository.findById(id).map(AttestationRepository.Stored::attestation);
    }

    private static Map<String, Object> entryMap(Attestation.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiSystemId", e.aiSystemId());
        m.put("name", e.name());
        m.put("riskTier", e.riskTier());
        m.put("approvalStatus", e.approvalStatus());
        m.put("posture", e.posture());
        m.put("detail", e.detail());
        return m;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
