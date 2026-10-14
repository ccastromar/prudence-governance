package com.prudence.oversight;

import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/oversight")
public class HumanOversightController {
    private final HumanAssignmentRepository assignments;
    private final HumanOverrideRepository overrides;
    public HumanOversightController(HumanAssignmentRepository assignments, HumanOverrideRepository overrides) {
        this.assignments = assignments; this.overrides = overrides;
    }
    @GetMapping("/systems/{id}/assignments")
    public List<HumanAssignmentJpaEntity> assignments(@PathVariable String id) { return assignments.findByAiSystemId(id); }
    @GetMapping("/systems/{id}/overrides")
    public List<HumanOverrideJpaEntity> overrides(@PathVariable String id) { return overrides.findByAiSystemIdOrderByOccurredAtDesc(id); }
    @PostMapping("/systems/{id}/assignments")
    public HumanAssignmentJpaEntity assign(@PathVariable String id, @RequestBody Body b) {
        if (b.role == null || blank(b.person) || blank(b.trainingEvidence) || blank(b.trainingExpiresAt)) throw new IllegalArgumentException("role, person, training evidence and expiry required");
        return assignments.save(new HumanAssignmentJpaEntity(UUID.randomUUID().toString(), id, b.role, b.person, b.overrideAuthority, b.stopAuthority, b.trainingEvidence, b.trainingExpiresAt));
    }
    @PostMapping("/systems/{id}/overrides")
    public HumanOverrideJpaEntity override(@PathVariable String id, @RequestBody OverrideBody b) {
        if (blank(b.aosReference) || blank(b.supervisor) || blank(b.reason) || blank(b.outcome)) throw new IllegalArgumentException("AOS reference, supervisor, reason and outcome required");
        return overrides.save(new HumanOverrideJpaEntity(UUID.randomUUID().toString(), id, b.aosReference, b.supervisor, b.reason, b.outcome, Instant.now().toString()));
    }
    @PostMapping("/overrides/{id}/review")
    public HumanOverrideJpaEntity review(@PathVariable String id, @RequestBody ReviewBody b) {
        if (blank(b.reviewer) || blank(b.note)) throw new IllegalArgumentException("reviewer and note required");
        var override = overrides.findById(id).orElseThrow(); override.review(b.reviewer, b.note, Instant.now().toString()); return overrides.save(override);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record Body(OversightRole role, String person, boolean overrideAuthority, boolean stopAuthority, String trainingEvidence, String trainingExpiresAt) {}
    public record OverrideBody(String aosReference, String supervisor, String reason, String outcome) {}
    public record ReviewBody(String reviewer, String note) {}
}
