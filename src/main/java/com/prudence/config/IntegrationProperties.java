package com.prudence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// Typed config for the control-plane integrations (OPA PDP sidecar, Ernest evidence).
@ConfigurationProperties(prefix = "prudence")
public record IntegrationProperties(Opa opa, Ernest ernest, Qtsp qtsp) {
    public record Opa(String baseUrl, String decisionPath) {}
    /// Ernest base URL and the credential Prudence presents (X-Ernest-Api-Key). The key is
    /// injected from the environment, never committed; blank when Ernest runs with open
    /// access. The same key authorizes evidence writes (/api/models, /api/inferences).
    public record Ernest(String baseUrl, String apiKey) {}
    /// QTSP remote-signing (CSC) endpoint used to qualify-seal evidence submissions
    /// (ADR-002 B2). Blank disables sealing (Prudence then only logs decisions). Injected
    /// from the environment.
    public record Qtsp(String cscUrl) {}
}
