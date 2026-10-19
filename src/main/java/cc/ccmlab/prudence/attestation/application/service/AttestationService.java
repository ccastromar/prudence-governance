package cc.ccmlab.prudence.attestation.application.service;

import cc.ccmlab.prudence.attestation.application.port.in.AttestationUseCase;
import cc.ccmlab.prudence.attestation.application.port.out.AttestationEvidenceQueryPort;
import cc.ccmlab.prudence.attestation.application.port.out.AttestationRepository;
import cc.ccmlab.prudence.attestation.domain.Attestation;
import cc.ccmlab.prudence.attestation.domain.Verification;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestSealer;
import cc.ccmlab.prudence.evidence.delivery.DeliverEvidence;
import cc.ccmlab.prudence.posture.application.port.in.PostureQuery;
import cc.ccmlab.prudence.posture.domain.Posture;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/// Builds a tamper-evident attestation of the estate and seals its hash in Ernest. Composes
/// the posture read model (what to report) with the evidence plane (how it's proved), so the
/// governance report is itself provable — ADR-000's closing step.
@Service
public class AttestationService implements AttestationUseCase {

  private static final String ATTESTATION_MODEL = "prudence-attestations";

  private final PostureQuery posture;
  private final DeliverEvidence delivery;
  private final AttestationEvidenceQueryPort evidenceQuery;
  private final AttestationRepository repository;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public AttestationService(
      PostureQuery posture,
      DeliverEvidence delivery,
      AttestationEvidenceQueryPort evidenceQuery,
      AttestationRepository repository) {
    this.posture = posture;
    this.delivery = delivery;
    this.evidenceQuery = evidenceQuery;
    this.repository = repository;
  }

  @Override
  public Attestation generate() {
    Posture p = posture.compute();
    String id = UUID.randomUUID().toString();
    String generatedAt = Instant.now().toString();
    String overall = p.overall().name();

    List<Attestation.Entry> entries =
        p.systems().stream()
            .sorted(Comparator.comparing(Posture.SystemPosture::id))
            .map(
                s ->
                    new Attestation.Entry(
                        s.id(),
                        s.name(),
                        s.riskTier(),
                        s.approvalStatus(),
                        s.status().name(),
                        s.detail()))
            .toList();

    // Deterministic report (hashed + stored). Keys are canonicalized on hashing, so
    // insertion order here is only for the stored, human-readable JSON.
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("id", id);
    report.put("generatedAt", generatedAt);
    report.put("overallPosture", overall);
    report.put("systems", entries.stream().map(AttestationService::entryMap).toList());

    String contentHash = ErnestSealer.sha256Canonical(report);

    // Stored first, then delivered: the report is durable before any network call, and a
    // failed delivery is retried in the background instead of issuing it unsealed for good.
    Attestation.Evidence pending =
        new Attestation.Evidence(
            false, null, ATTESTATION_MODEL, "Queued for recording in Ernest.", "PENDING");
    repository.save(
        new Attestation(id, generatedAt, overall, contentHash, entries, pending), toJson(report));
    delivery.deliverNow("attestations");
    return repository.findById(id).map(AttestationRepository.Stored::attestation).orElseThrow();
  }

  @Override
  public List<Summary> list() {
    return repository.findAllNewestFirst().stream()
        .map(
            s -> {
              Attestation a = s.attestation();
              return new Summary(
                  a.id(),
                  a.generatedAt(),
                  a.overallPosture(),
                  a.contentHash(),
                  a.evidence().sealed(),
                  a.evidence().blockIndex(),
                  a.evidence().delivery());
            })
        .toList();
  }

  @Override
  public Optional<Attestation> get(String id) {
    return repository.findById(id).map(AttestationRepository.Stored::attestation);
  }

  @Override
  public Optional<Verification> verify(String id) {
    return repository
        .findById(id)
        .map(
            stored -> {
              Attestation a = stored.attestation();

              // Recompute the hash from the stored report — detects tampering of the report
              // (or of the stored hash column).
              String recomputed;
              try {
                Map<String, Object> report = mapper.readValue(stored.reportJson(), Map.class);
                recomputed = ErnestSealer.sha256Canonical(report);
              } catch (Exception e) {
                recomputed = "";
              }
              boolean hashMatches = !recomputed.isEmpty() && recomputed.equals(a.contentHash());

              boolean sealed = a.evidence().sealed(); // qualified seal
              String delivery = a.evidence().delivery();
              boolean recorded = "DELIVERED".equals(delivery);
              boolean confirmed = false, chainValid = false, pendingInErnest = false;
              boolean custodyOnly = false;
              Long blockIndex = a.evidence().blockIndex();
              String detail;
              // A report recorded without a qualified seal is still confirmable in Ernest.
              if (recorded) {
                AttestationEvidenceQueryPort.Confirmation c =
                    evidenceQuery.confirm(ATTESTATION_MODEL, a.id(), a.contentHash());
                confirmed = c.present();
                pendingInErnest = c.pending();
                chainValid = c.chainValid();
                if (c.blockIndex() != null) blockIndex = c.blockIndex();
                if (!c.verifiable()) {
                  custodyOnly = true;
                  detail =
                      "Report is held in a custody-only evidence store, which cannot confirm"
                          + " whether a record is present or intact: this attestation cannot be"
                          + " verified independently of that store.";
                } else if (!c.reachable())
                  detail = "Evidence plane unreachable — the seal could not be confirmed.";
                else if (c.pending())
                  detail =
                      "Report is recorded in Ernest and waiting to be chained into the next batch.";
                else if (c.present() && c.chainValid())
                  detail =
                      sealed
                          ? "Report hash is qualified-sealed in Ernest and the chain verifies."
                          : "Report hash is recorded in Ernest (no qualified seal) and the chain"
                              + " verifies.";
                else if (c.present())
                  detail = "Seal present, but the Ernest chain does not verify.";
                else detail = "No Ernest block carries this report hash.";
              } else if ("PENDING".equals(delivery)) {
                detail = "Report is queued for recording in Ernest; delivery is being retried.";
              } else {
                detail = a.evidence().detail();
              }

              String verdict;
              if (!hashMatches) verdict = "ALTERED";
              else if ("PENDING".equals(delivery)) verdict = "PENDING";
              else if (recorded && pendingInErnest) verdict = "PENDING";
              else if (!recorded) verdict = "UNSEALED";
              else if (custodyOnly) verdict = "CUSTODY_ONLY";
              else if (confirmed && chainValid) verdict = "VERIFIED";
              else verdict = "UNCONFIRMED";

              return new Verification(
                  a.id(),
                  hashMatches,
                  recomputed,
                  a.contentHash(),
                  sealed,
                  blockIndex,
                  confirmed,
                  chainValid,
                  verdict,
                  detail);
            });
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
