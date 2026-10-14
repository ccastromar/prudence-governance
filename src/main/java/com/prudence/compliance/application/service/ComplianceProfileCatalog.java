package com.prudence.compliance.application.service;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.RegulatoryRole;
import com.prudence.registry.domain.RiskTier;
import org.springframework.stereotype.Component;

import java.util.List;

/// Versioned profiles are code, not mutable database configuration: changing an obligation is a
/// reviewed release. Prudence stores the assigned profile id on every generated control.
@Component
final class ComplianceProfileCatalog {
    record Definition(String key, String title, String requirement, boolean blocking, String accepts,
                      Integer expiresAfterDays, boolean expiresOnVersionChange, boolean blocksVersionChange, String source) {}
    record Profile(String id, List<Definition> controls) {}

    private static final List<Definition> PROVIDER_HIGH = List.of(
            control("RISK_MANAGEMENT", "Risk management system", "Current risk assessment, mitigations and residual-risk acceptance."),
            dataControl("DATA_GOVERNANCE", "Data governance evidence", "Versioned data governance and quality assessment for the intended purpose."),
            control("TECHNICAL_DOCUMENTATION", "Technical documentation", "Current technical dossier and instructions for use."),
            control("LOGGING_TRACEABILITY", "Logging and traceability", "Logging design and retained traceability evidence."),
            control("HUMAN_OVERSIGHT", "Human oversight design", "Documented human oversight measures and override/stop authority."),
            control("ROBUSTNESS_SECURITY", "Accuracy, robustness and cybersecurity", "Current performance, robustness and cybersecurity assessment."),
            control("QUALITY_MANAGEMENT", "Quality management", "Quality-management evidence covering the system lifecycle."),
            control("CONFORMITY_ASSESSMENT", "Conformity assessment", "Applicable conformity assessment and declaration evidence."),
            control("POST_MARKET_MONITORING", "Post-market monitoring", "Monitoring plan, signals and corrective-action ownership."));
    private static final List<Definition> DEPLOYER_HIGH = List.of(
            control("INSTRUCTIONS_OF_USE", "Provider instructions", "Current provider instructions accepted for this deployment."),
            control("HUMAN_OVERSIGHT_ASSIGNMENT", "Human oversight assignment", "Named supervisors with authority, competence and escalation path."),
            control("INPUT_DATA_RELEVANCE", "Input-data relevance", "Evidence that deployer-controlled input data is relevant and representative."),
            control("OPERATION_MONITORING", "Operational monitoring", "Monitoring plan and responsible operator for production use."),
            control("INCIDENT_ESCALATION", "Incident escalation", "Incident/provider/authority escalation procedure tested for this system."));

    List<Profile> applicableTo(AiSystem system) {
        if (system.riskTier() != RiskTier.HIGH) return List.of();
        return switch (system.classification().regulatoryRole()) {
            case PROVIDER -> List.of(new Profile("EU_AI_ACT_PROVIDER_HIGH_RISK_V1", PROVIDER_HIGH));
            case DEPLOYER -> List.of(new Profile("EU_AI_ACT_DEPLOYER_HIGH_RISK_V1", DEPLOYER_HIGH));
            // Importers and distributors have distinct obligations; they need a dedicated
            // profile rather than silently inheriting provider controls.
            case IMPORTER, DISTRIBUTOR -> List.of();
        };
    }

    private static Definition control(String key, String title, String requirement) {
        return new Definition(key, title, requirement, true, "URL,SHA-256,version,owner,signed-attestation", 365, true, true, "MANUAL_OR_GRC");
    }
    private static Definition dataControl(String key, String title, String requirement) {
        return new Definition(key, title, requirement, true, "URL,SHA-256,dataset-version,metrics-by-group,approver,signed-attestation",
                365, true, true, "MLOPS_OR_GRC");
    }
}
