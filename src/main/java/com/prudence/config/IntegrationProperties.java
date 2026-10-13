package com.prudence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/// Typed config for the control-plane integrations (OPA PDP sidecar, Ernest evidence).
@ConfigurationProperties(prefix = "prudence")
public record IntegrationProperties(Opa opa, Ernest ernest, Qtsp qtsp) {
    public record Opa(String baseUrl, String decisionPath, String policyVersion, String policyHash) {}
    /// Ernest base URL and the credential Prudence presents (X-Ernest-Api-Key). The key is
    /// injected from the environment, never committed; blank when Ernest runs with open
    /// access. The same key authorizes evidence writes (/api/models, /api/inferences).
    ///
    /// `decisionTransport` picks how PDP decisions — the high-volume evidence stream — reach
    /// Ernest: `direct` (POST /api/inferences, synchronous append, qualified-sealable) or
    /// `ingestor` (POST /events on Ernest's event-ingestor at `ingestorUrl`, presenting
    /// `ingestorKey` as X-Ernest-Ingest-Key: queued and deduped by the decision id, never
    /// qualified-sealed). Reviews, attestations and incidents always go direct.
    public record Ernest(String baseUrl, String apiKey, String decisionTransport, String ingestorUrl,
                         String ingestorKey) {
        /// The constructor configuration binds through (the convenience one below would
        /// otherwise make binding ambiguous and leave `prudence.ernest` unbound).
        @ConstructorBinding
        public Ernest {}

        public Ernest(String baseUrl, String apiKey) {
            this(baseUrl, apiKey, "direct", null, null);
        }

        public boolean decisionsViaIngestor() {
            String t = decisionTransport == null || decisionTransport.isBlank() ? "direct" : decisionTransport.strip();
            return switch (t) {
                case "direct" -> false;
                case "ingestor" -> true;
                default -> throw new IllegalStateException(
                        "prudence.ernest.decision-transport must be 'direct' or 'ingestor' (was '" + t + "')");
            };
        }
    }
    /// QTSP remote-signing (CSC) endpoint used to qualify-seal evidence submissions
    /// (ADR-002 B2). Blank disables sealing (evidence is then recorded unsealed). Injected
    /// from the environment.
    public record Qtsp(String cscUrl) {}
}
