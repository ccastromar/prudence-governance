package com.prudence.attestation.adapter.out.persistence;

import tools.jackson.databind.json.JsonMapper;
import com.prudence.attestation.application.port.out.AttestationRepository;
import com.prudence.attestation.domain.Attestation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Driven adapter: persists attestations over JPA. Scalar fields are columns; the hashed
/// report (with the per-system entries) is kept as JSON and re-parsed on read.
@Component
public class AttestationPersistenceAdapter implements AttestationRepository {

    private final AttestationJpaRepository jpa;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AttestationPersistenceAdapter(AttestationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Attestation a, String reportJson) {
        Attestation.Evidence ev = a.evidence();
        jpa.save(new AttestationJpaEntity(a.id(), a.generatedAt(), a.overallPosture(), a.contentHash(),
                ev.sealed(), ev.blockIndex(), ev.modelId(), ev.detail(), reportJson));
    }

    @Override
    public List<Stored> findAllNewestFirst() {
        return jpa.findAllByOrderByGeneratedAtDesc().stream()
                .map(e -> new Stored(toAttestation(e, false), e.getReportJson()))
                .toList();
    }

    @Override
    public Optional<Stored> findById(String id) {
        return jpa.findById(id).map(e -> new Stored(toAttestation(e, true), e.getReportJson()));
    }

    private Attestation toAttestation(AttestationJpaEntity e, boolean withSystems) {
        List<Attestation.Entry> systems = withSystems ? parseSystems(e.getReportJson()) : List.of();
        Attestation.Evidence ev = new Attestation.Evidence(
                e.isSealed(), e.getBlockIndex(), e.getModelId(), e.getSealDetail());
        return new Attestation(e.getId(), e.getGeneratedAt(), e.getOverallPosture(),
                e.getContentHash(), systems, ev);
    }

    @SuppressWarnings("unchecked")
    private List<Attestation.Entry> parseSystems(String reportJson) {
        try {
            Map<String, Object> report = mapper.readValue(reportJson, Map.class);
            List<Map<String, Object>> sys = (List<Map<String, Object>>) report.getOrDefault("systems", List.of());
            return sys.stream().map(m -> new Attestation.Entry(
                    str(m, "aiSystemId"), str(m, "name"), str(m, "riskTier"),
                    str(m, "approvalStatus"), str(m, "posture"), str(m, "detail"))).toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
