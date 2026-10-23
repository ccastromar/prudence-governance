package cc.ccmlab.prudence.compliance.application.service;

import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.List;
import org.springframework.stereotype.Component;

/// Versioned profiles are code, not mutable database configuration: changing an obligation is a
/// reviewed release. Prudence stores the assigned profile id on every generated control.
@Component
final class ComplianceProfileCatalog {
  record Definition(
      String key,
      String title,
      String requirement,
      boolean blocking,
      String accepts,
      Integer expiresAfterDays,
      boolean expiresOnVersionChange,
      boolean blocksVersionChange,
      String source) {}

  record Profile(String id, List<Definition> controls) {}

  private static final List<Definition> PROVIDER_HIGH =
      List.of(
          control(
              "RISK_MANAGEMENT",
              "Risk management system",
              "Current risk assessment, mitigations and residual-risk acceptance."),
          dataControl(
              "DATA_GOVERNANCE",
              "Data governance evidence",
              "Versioned data governance and quality assessment for the intended purpose."),
          control(
              "TECHNICAL_DOCUMENTATION",
              "Technical documentation",
              "Current technical dossier and instructions for use."),
          control(
              "LOGGING_TRACEABILITY",
              "Logging and traceability",
              "Logging design and retained traceability evidence."),
          control(
              "HUMAN_OVERSIGHT",
              "Human oversight design",
              "Documented human oversight measures and override/stop authority."),
          control(
              "ROBUSTNESS_SECURITY",
              "Accuracy, robustness and cybersecurity",
              "Current performance, robustness and cybersecurity assessment."),
          control(
              "QUALITY_MANAGEMENT",
              "Quality management",
              "Quality-management evidence covering the system lifecycle."),
          control(
              "CONFORMITY_ASSESSMENT",
              "Conformity assessment",
              "Applicable conformity assessment and declaration evidence."),
          control(
              "POST_MARKET_MONITORING",
              "Post-market monitoring",
              "Monitoring plan, signals and corrective-action ownership."));
  private static final List<Definition> DEPLOYER_HIGH =
      List.of(
          control(
              "INSTRUCTIONS_OF_USE",
              "Provider instructions",
              "Current provider instructions accepted for this deployment."),
          control(
              "HUMAN_OVERSIGHT_ASSIGNMENT",
              "Human oversight assignment",
              "Named supervisors with authority, competence and escalation path."),
          control(
              "INPUT_DATA_RELEVANCE",
              "Input-data relevance",
              "Evidence that deployer-controlled input data is relevant and representative."),
          control(
              "OPERATION_MONITORING",
              "Operational monitoring",
              "Monitoring plan and responsible operator for production use."),
          control(
              "INCIDENT_ESCALATION",
              "Incident escalation",
              "Incident/provider/authority escalation procedure tested for this system."));
  private static final List<Definition> IMPORTER_HIGH =
      List.of(
          control(
              "IMPORTER_CONFORMITY_CHECK",
              "Pre-market conformity verification",
              "Art. 23(1): verify CE marking, EU declaration of conformity, instructions for use, "
                  + "that the provider completed the applicable conformity assessment and drew "
                  + "up the technical documentation, and that an authorised representative has "
                  + "been appointed where required."),
          control(
              "IMPORTER_IDENTIFICATION",
              "Importer identification",
              "Art. 23(3): ensure the importer's name and contact address appear on the system, "
                  + "packaging or accompanying documentation, as applicable."),
          control(
              "IMPORTER_STORAGE_HANDLING",
              "Storage and transport safeguards",
              "Art. 23(4): document that storage and transport under the importer's "
                  + "responsibility do not jeopardise compliance."),
          control(
              "IMPORTER_DOCUMENT_RETENTION",
              "Importer document retention",
              "Arts. 23(5)-23(6): retain the EU declaration of conformity, instructions for use "
                  + "and (where applicable) the notified-body certificate for ten years; make "
                  + "technical documentation available to authorities on reasoned request."),
          control(
              "IMPORTER_RISK_RESPONSE",
              "Importer risk and corrective-action procedure",
              "Arts. 23(2), 23(7): do not place non-compliant or falsified systems on the market; "
                  + "notify the provider, authorised representative and authorities when a risk "
                  + "is identified, and cooperate with competent authorities."));
  private static final List<Definition> DISTRIBUTOR_HIGH =
      List.of(
          control(
              "DISTRIBUTOR_CONFORMITY_CHECK",
              "Pre-market distributor verification",
              "Art. 24(1): verify CE marking, required documentation and instructions, and that "
                  + "the provider and importer have met their applicable obligations before "
                  + "making the system available."),
          control(
              "DISTRIBUTOR_STORAGE_HANDLING",
              "Storage and transport safeguards",
              "Art. 24(2): document that storage and transport conditions preserve the system's "
                  + "compliance with the applicable requirements."),
          control(
              "DISTRIBUTOR_RISK_RESPONSE",
              "Distributor risk and corrective-action procedure",
              "Arts. 24(2), 24(4)-24(6): withhold a non-compliant system; notify the provider or "
                  + "importer when a risk is identified; where already made available, take or "
                  + "ensure corrective action, withdrawal or recall and notify authorities; "
                  + "provide requested information and cooperate with authorities."));

  List<Profile> applicableTo(AiSystem system) {
    if (system.riskTier() != RiskTier.HIGH) return List.of();
    return switch (system.classification().regulatoryRole()) {
      case PROVIDER -> List.of(new Profile("EU_AI_ACT_PROVIDER_HIGH_RISK_V1", PROVIDER_HIGH));
      case DEPLOYER -> List.of(new Profile("EU_AI_ACT_DEPLOYER_HIGH_RISK_V1", DEPLOYER_HIGH));
      case IMPORTER -> List.of(new Profile("EU_AI_ACT_IMPORTER_HIGH_RISK_V1", IMPORTER_HIGH));
      case DISTRIBUTOR ->
          List.of(new Profile("EU_AI_ACT_DISTRIBUTOR_HIGH_RISK_V1", DISTRIBUTOR_HIGH));
    };
  }

  private static Definition control(String key, String title, String requirement) {
    return new Definition(
        key,
        title,
        requirement,
        true,
        "URL,SHA-256,version,owner,signed-attestation",
        365,
        true,
        true,
        "MANUAL_OR_GRC");
  }

  private static Definition dataControl(String key, String title, String requirement) {
    return new Definition(
        key,
        title,
        requirement,
        true,
        "URL,SHA-256,dataset-version,metrics-by-group,approver,signed-attestation",
        365,
        true,
        true,
        "MLOPS_OR_GRC");
  }
}
