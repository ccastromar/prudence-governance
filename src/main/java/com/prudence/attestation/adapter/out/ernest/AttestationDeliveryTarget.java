package com.prudence.attestation.adapter.out.ernest;

import com.prudence.attestation.adapter.out.persistence.AttestationJpaEntity;
import com.prudence.attestation.adapter.out.persistence.AttestationJpaRepository;
import com.prudence.attestation.application.port.out.AttestationSealPort;
import com.prudence.evidence.delivery.Delivery;
import com.prudence.evidence.delivery.DeliveryTarget;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/// Attestation report hashes → Ernest (model "prudence-attestations"), under the attestation id.
@Component
public class AttestationDeliveryTarget implements DeliveryTarget<AttestationJpaEntity> {

    public static final String NAME = "attestations";

    private final AttestationJpaRepository attestations;
    private final AttestationSealPort seal;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AttestationDeliveryTarget(AttestationJpaRepository attestations, AttestationSealPort seal) {
        this.attestations = attestations;
        this.seal = seal;
    }

    @Override public String name() { return NAME; }
    @Override public List<AttestationJpaEntity> due(String now, int limit) { return attestations.due(now, Limit.of(limit)); }
    @Override public String id(AttestationJpaEntity e) { return e.getId(); }
    @Override public Delivery delivery(AttestationJpaEntity e) { return e.getDelivery(); }
    @Override public int claim(String id, String expected, String leaseUntil) { return attestations.claim(id, expected, leaseUntil); }
    @Override public Optional<AttestationJpaEntity> find(String id) { return attestations.findById(id); }
    @Override public void save(AttestationJpaEntity e) { attestations.save(e); }

    @Override
    public Consumer<AttestationJpaEntity> send(AttestationJpaEntity e) throws Exception {
        AttestationSealPort.Receipt receipt = seal.seal(e.getId(), e.getContentHash(), summary(e));
        return record -> record.recordSeal(receipt.qualified(), receipt.blockIndex(), receipt.detail());
    }

    /// The signed metadata, rebuilt from the stored report so every retry sends identical bytes.
    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(AttestationJpaEntity e) {
        Map<String, Object> report = mapper.readValue(e.getReportJson(), Map.class);
        Object systems = report.get("systems");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", e.getGeneratedAt());
        summary.put("overallPosture", e.getOverallPosture());
        summary.put("systemCount", String.valueOf(systems instanceof List<?> l ? l.size() : 0));
        return summary;
    }
}
