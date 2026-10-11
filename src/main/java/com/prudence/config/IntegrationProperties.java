package com.prudence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// Typed config for the control-plane integrations (OPA PDP sidecar, Ernest evidence).
@ConfigurationProperties(prefix = "prudence")
public record IntegrationProperties(Opa opa, Ernest ernest) {
    public record Opa(String baseUrl, String decisionPath) {}
    /// Ernest base URL and the read credential Prudence presents (X-Ernest-Api-Key). The key
    /// is injected from the environment, never committed; blank when Ernest reads are open.
    public record Ernest(String baseUrl, String apiKey) {}
}
