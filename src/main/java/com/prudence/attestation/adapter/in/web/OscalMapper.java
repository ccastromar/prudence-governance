package com.prudence.attestation.adapter.in.web;

import com.prudence.attestation.domain.Attestation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Maps an attestation to a NIST OSCAL **Assessment Results** document — machine-readable
/// compliance (the "serious, not a CRUD" artifact of ADR-000). Each registered AI system
/// becomes an observation; its posture becomes a finding (GREEN = satisfied, else
/// not-satisfied); the Ernest seal is recorded in back-matter so the OSCAL report points at
/// the tamper-evident evidence it derives from.
///
/// Structurally faithful to the OSCAL AR model's core fields; not yet run through the
/// official OSCAL validator (a follow-up).
final class OscalMapper {

    private OscalMapper() {}

    static Map<String, Object> toAssessmentResults(Attestation a) {
        Map<String, Object> ar = new LinkedHashMap<>();
        ar.put("uuid", UUID.randomUUID().toString());
        ar.put("metadata", metadata(a));
        ar.put("import-ap", Map.of("href", "#prudence-ai-governance"));
        ar.put("results", List.of(result(a)));
        ar.put("back-matter", backMatter(a));
        return Map.of("assessment-results", ar);
    }

    private static Map<String, Object> metadata(Attestation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Prudence AI governance attestation " + a.id());
        m.put("last-modified", a.generatedAt());
        m.put("version", a.id());
        m.put("oscal-version", "1.1.2");
        m.put("props", List.of(
                prop("marking", a.overallPosture()),
                prop("content-hash", a.contentHash())));
        return m;
    }

    private static Map<String, Object> result(Attestation a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uuid", UUID.randomUUID().toString());
        r.put("title", "AI estate governance assessment");
        r.put("description", "Posture of the registered AI estate at attestation time, "
                + "proved by the Ernest evidence plane.");
        r.put("start", a.generatedAt());
        r.put("reviewed-controls", Map.of("control-selections", List.of(Map.of("include-all", Map.of()))));

        List<Map<String, Object>> observations = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Attestation.Entry e : a.systems()) {
            observations.add(observation(e, a.generatedAt()));
            findings.add(finding(e));
        }
        r.put("observations", observations);
        r.put("findings", findings);
        return r;
    }

    private static Map<String, Object> observation(Attestation.Entry e, String collected) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("uuid", UUID.randomUUID().toString());
        o.put("title", e.name());
        o.put("description", "Registry record and posture for AI system " + e.aiSystemId() + ".");
        o.put("methods", List.of("EXAMINE"));
        o.put("collected", collected);
        o.put("subjects", List.of(subjectRef(e.aiSystemId())));
        o.put("props", List.of(
                prop("risk-tier", e.riskTier()),
                prop("approval-status", e.approvalStatus()),
                prop("posture", e.posture())));
        return o;
    }

    private static Map<String, Object> subjectRef(String aiSystemId) {
        Map<String, Object> s = new LinkedHashMap<>();
        // Registry ids are UUIDs, so they double as OSCAL subject-uuids.
        s.put("subject-uuid", aiSystemId);
        s.put("type", "component");
        return s;
    }

    private static Map<String, Object> finding(Attestation.Entry e) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("uuid", UUID.randomUUID().toString());
        f.put("title", "Governance posture: " + e.name());
        f.put("description", e.detail());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "objective-id");
        // OSCAL target-id is a token (must start with a letter/underscore), so prefix the UUID.
        target.put("target-id", "system-" + e.aiSystemId());
        target.put("status", Map.of("state", "GREEN".equals(e.posture()) ? "satisfied" : "not-satisfied"));
        f.put("target", target);
        return f;
    }

    private static Map<String, Object> backMatter(Attestation a) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uuid", UUID.randomUUID().toString());
        resource.put("title", "Ernest tamper-evident evidence seal");
        List<Map<String, Object>> props = new ArrayList<>();
        props.add(prop("content-hash", a.contentHash()));
        props.add(prop("sealed", String.valueOf(a.evidence().sealed())));
        props.add(prop("evidence-delivery", a.evidence().delivery()));
        props.add(prop("evidence-model", a.evidence().modelId()));
        if (a.evidence().blockIndex() != null) props.add(prop("ernest-block", String.valueOf(a.evidence().blockIndex())));
        resource.put("props", props);
        resource.put("description", a.evidence().detail());
        return Map.of("resources", List.of(resource));
    }

    private static Map<String, Object> prop(String name, String value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("value", value == null ? "" : value);
        p.put("ns", "https://prudence.governance/ns/oscal");
        return p;
    }
}
