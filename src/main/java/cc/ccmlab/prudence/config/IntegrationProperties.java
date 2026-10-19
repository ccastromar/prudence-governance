package cc.ccmlab.prudence.config;

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
  /// Ernest: `direct` (POST /api/inferences/batch, qualified-sealable), `ingestor` (POST
  /// /events on Ernest's event-ingestor at `ingestorUrl`, presenting `ingestorKey` as
  /// X-Ernest-Ingest-Key) or `kafka` (Ernest ADR-005: records on `kafkaTopic` at
  /// `kafkaBootstrapServers`, consumed by the ingestor's Kafka source). The last two are
  /// deduped by the decision id and never qualified-sealed. Reviews, attestations and
  /// incidents always go direct.
  ///
  /// `baseUrl` serves reads (posture, drift, proofs, verification). `writeBaseUrl`, when set,
  /// receives the direct writes instead — an Ernest deployment split into read replicas
  /// (`ERNEST_ROLE=reader`) and writers; blank means one Ernest serves both.
  ///
  /// Kafka security (decision-transport=kafka): `kafkaSecurityProtocol` is PLAINTEXT, SSL,
  /// SASL_PLAINTEXT or SASL_SSL (production requires SSL or SASL_SSL); SASL uses
  /// `kafkaSaslMechanism` (SCRAM-SHA-512 by default, SCRAM-SHA-256 or PLAIN) with
  /// `kafkaSaslUsername`/`kafkaSaslPassword`; `kafkaTruststoreLocation`/`Password` trust a
  /// private CA. `kafkaProperties` passes any other Kafka client property through verbatim.
  public record Ernest(
      String baseUrl,
      String writeBaseUrl,
      String apiKey,
      String decisionTransport,
      String ingestorUrl,
      String ingestorKey,
      String kafkaBootstrapServers,
      String kafkaTopic,
      String kafkaSecurityProtocol,
      String kafkaSaslMechanism,
      String kafkaSaslUsername,
      String kafkaSaslPassword,
      String kafkaTruststoreLocation,
      String kafkaTruststorePassword,
      java.util.Map<String, String> kafkaProperties) {
    /// The constructor configuration binds through (the convenience one below would
    /// otherwise make binding ambiguous and leave `prudence.ernest` unbound).
    @ConstructorBinding
    public Ernest {}

    public Ernest(String baseUrl, String apiKey) {
      this(
          baseUrl, null, apiKey, "direct", null, null, null, null, null, null, null, null, null,
          null, null);
    }

    /// Where direct writes go: the dedicated writer when configured, else `baseUrl`.
    public String writeUrl() {
      return writeBaseUrl == null || writeBaseUrl.isBlank() ? baseUrl : writeBaseUrl.strip();
    }

    public boolean decisionsViaIngestor() {
      return "ingestor".equals(transport());
    }

    public boolean decisionsViaKafka() {
      return "kafka".equals(transport());
    }

    private String transport() {
      String t =
          decisionTransport == null || decisionTransport.isBlank()
              ? "direct"
              : decisionTransport.strip();
      return switch (t) {
        case "direct", "ingestor", "kafka" -> t;
        default ->
            throw new IllegalStateException(
                "prudence.ernest.decision-transport must be 'direct', 'ingestor' or 'kafka' (was '"
                    + t
                    + "')");
      };
    }
  }

  /// QTSP remote-signing (CSC) endpoint used to qualify-seal evidence submissions
  /// (ADR-002 B2). Blank disables sealing (evidence is then recorded unsealed). Injected
  /// from the environment.
  public record Qtsp(String cscUrl) {}
}
