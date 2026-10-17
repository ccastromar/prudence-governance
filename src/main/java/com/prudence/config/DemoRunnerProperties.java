package com.prudence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Private bridge to the framework demonstration runner. The browser never receives either this
 * bearer token or the runner's M2M and Ernest credentials.
 */
@ConfigurationProperties(prefix = "prudence.demo-runner")
public record DemoRunnerProperties(String baseUrl, String token) {
  public boolean configured() {
    return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
  }
}
