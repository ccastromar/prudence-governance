package com.prudence.compliance.application.service;

import com.prudence.compliance.adapter.out.persistence.ComplianceControlJpaEntity;
import com.prudence.compliance.adapter.out.persistence.ComplianceControlJpaRepository;
import com.prudence.compliance.application.port.in.ComplianceControls;
import com.prudence.compliance.domain.ComplianceControlStatus;
import com.prudence.registry.domain.AiSystem;
import com.prudence.shared.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ComplianceControlService implements ComplianceControls {
    private final ComplianceControlJpaRepository controls;
    private final ComplianceProfileCatalog catalog;

    public ComplianceControlService(ComplianceControlJpaRepository controls, ComplianceProfileCatalog catalog) {
        this.controls = controls;
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public void initialize(AiSystem system) {
        for (ComplianceProfileCatalog.Profile profile : catalog.applicableTo(system)) {
            if (controls.existsByAiSystemIdAndProfileId(system.id().value(), profile.id())) continue;
            String now = Instant.now().toString();
            controls.saveAll(profile.controls().stream().map(definition -> new ComplianceControlJpaEntity(
                    UUID.randomUUID().toString(), system.id().value(), profile.id(), definition.key(), definition.title(),
                    definition.requirement(), definition.blocking(), now)).toList());
        }
    }

    @Override
    @Transactional
    public List<ComplianceControlJpaEntity> initializeAndList(AiSystem system) {
        initialize(system);
        return forSystem(system.id().value());
    }

    @Override
    @Transactional
    public void requireReadyForReview(AiSystem system) {
        initialize(system); // also upgrades legacy systems the first time they enter review.
        List<String> missing = forSystem(system.id().value()).stream()
                .filter(ComplianceControlJpaEntity::isBlocking)
                .filter(control -> !satisfiedAndCurrent(control))
                .map(control -> control.getControlKey() + " (" + control.getTitle() + ")").toList();
        if (!missing.isEmpty()) {
            throw new DomainException("compliance controls required before review: " + String.join(", ", missing));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceControlJpaEntity> forSystem(String aiSystemId) {
        return controls.findByAiSystemIdOrderByProfileIdAscControlKeyAsc(aiSystemId);
    }

    @Override
    @Transactional
    public ComplianceControlJpaEntity satisfy(String aiSystemId, String controlId, Evidence evidence) {
        ComplianceControlJpaEntity control = controls.findByIdAndAiSystemId(controlId, aiSystemId)
                .orElseThrow(() -> new DomainException("compliance control not found: " + controlId));
        validateEvidence(evidence);
        control.satisfy(evidence.uri(), evidence.sha256(), evidence.version(), evidence.owner(), Instant.now().toString(), evidence.expiresAt());
        return controls.save(control);
    }

    private static boolean satisfiedAndCurrent(ComplianceControlJpaEntity control) {
        if (control.getStatus() != ComplianceControlStatus.SATISFIED) return false;
        return control.getExpiresAt() == null || control.getExpiresAt().isBlank() || Instant.parse(control.getExpiresAt()).isAfter(Instant.now());
    }

    private static void validateEvidence(Evidence evidence) {
        if (evidence == null || blank(evidence.uri()) || blank(evidence.sha256()) || blank(evidence.version()) || blank(evidence.owner())) {
            throw new DomainException("evidence uri, sha256, version and owner are required");
        }
        String hash = evidence.sha256().startsWith("sha256:") ? evidence.sha256().substring(7) : evidence.sha256();
        if (!hash.matches("[a-fA-F0-9]{64}")) throw new DomainException("evidence sha256 must be a SHA-256 hex digest");
        if (!blank(evidence.expiresAt())) {
            try { Instant.parse(evidence.expiresAt()); }
            catch (Exception e) { throw new DomainException("evidence expiresAt must be an ISO-8601 instant"); }
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
