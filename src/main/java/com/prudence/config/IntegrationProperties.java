package com.prudence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// Typed config for the control-plane integrations (OPA PDP sidecar, Ernest evidence).
@ConfigurationProperties(prefix = "prudence")
public record IntegrationProperties(Opa opa, Ernest ernest) {
    public record Opa(String baseUrl, String decisionPath) {}
    public record Ernest(String baseUrl) {}
}
