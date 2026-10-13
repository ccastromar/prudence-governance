package com.prudence.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ADR-000 / SSO: Prudence as an OAuth2 resource server validating the Chestertown IdP's JWTs
 * (Keycloak realm). Enabled only when an issuer is configured
 * (spring.security.oauth2.resourceserver.jwt.issuer-uri); otherwise a permit-all chain keeps
 * dev and tests unauthenticated. Realm roles arrive in the `roles` claim and become
 * ROLE_* authorities. The machine-to-machine PDP endpoint (/api/policy/decisions) is secured
 * with the `pdp.decide` scope: a caller (AOS gate, runtime adapter) presents a
 * client-credentials token carrying that scope. Granted scopes map to SCOPE_* authorities.
 */
@Configuration
public class SecurityConfig {

  @Bean
  @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")
  @Profile("!dev & !test")
  SecurityFilterChain secured(HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
    http
        .cors(c -> c.configurationSource(cors))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health").permitAll()
            // The PDP is a machine-to-machine endpoint: an AOS gate or a runtime adapter
            // calls it with a client-credentials token. Require the `pdp.decide` scope
            // (a human SSO token does not carry it, so humans can't drive the PDP directly).
            .requestMatchers(HttpMethod.POST, "/api/policy/decisions").hasAuthority("SCOPE_pdp.decide")
            .requestMatchers(HttpMethod.POST, "/api/registry/systems", "/api/registry/systems/*/submit").hasRole("read-write")
            // Registering an incident and recording its reports change the register (and may
            // suspend a system); reading it only needs authentication.
            .requestMatchers(HttpMethod.POST, "/api/incidents", "/api/incidents/**").hasRole("read-write")
            // Re-driving dead-lettered evidence is an operator act.
            .requestMatchers(HttpMethod.POST, "/api/evidence/**").hasRole("read-write")
            // Approving/rejecting/renewing a system and issuing an attestation are reviewer acts.
            .requestMatchers(HttpMethod.POST, "/api/registry/systems/*/approve", "/api/registry/systems/*/reject",
                    "/api/registry/systems/*/renew", "/api/attestations")
                .hasRole("read-write")
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())));
    return http.build();
  }

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  @Profile({"dev", "test"})
  SecurityFilterChain open(HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
    http
        .cors(c -> c.configurationSource(cors))
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  /** Production must never silently fall back to an unauthenticated control plane. */
  @Bean
  @Profile("!dev & !test")
  org.springframework.boot.ApplicationRunner requireProductionSecurity(Environment environment) {
    return ignored -> {
      requireNonBlank(environment, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
      requireNonBlank(environment, "spring.datasource.url");
      requireNonBlank(environment, "spring.datasource.username");
      requireNonBlank(environment, "spring.datasource.password");
      requireNonBlank(environment, "prudence.ernest.base-url");
      requireNonBlank(environment, "prudence.ernest.api-key");
      if ("ingestor".equals(environment.getProperty("prudence.ernest.decision-transport", "direct").strip())) {
        // An ingestor without a shared secret accepts anyone's events as "unverified".
        requireNonBlank(environment, "prudence.ernest.ingestor-url");
        requireNonBlank(environment, "prudence.ernest.ingestor-key");
      }
      // Alerts are how humans learn about expiries, drift and lost evidence: no silent channel.
      requireNonBlank(environment, "spring.mail.host");
      requireNonBlank(environment, "prudence.alerts.governance-recipients");
      requireNonBlank(environment, "prudence.alerts.operations-recipients");
      String origins = System.getenv("PRUDENCE_CORS_ORIGINS");
      if (origins == null || origins.isBlank()) {
        throw new IllegalStateException("Refusing to start outside dev/test without PRUDENCE_CORS_ORIGINS");
      }
    };
  }

  private static void requireNonBlank(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Refusing to start outside dev/test without " + property);
    }
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    String origins = System.getenv().getOrDefault("PRUDENCE_CORS_ORIGINS", "http://localhost:5200,http://127.0.0.1:5200");
    cfg.setAllowedOrigins(List.of(origins.split(",")));
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cfg.setAllowedHeaders(List.of("*"));
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", cfg);
    return src;
  }

  /**
   * Map Keycloak realm roles (the `roles` claim) to ROLE_* authorities, and resolve the
   * principal name to the identity OPA and the registry know: the `email` for humans,
   * `preferred_username` for service accounts (e.g. `service-account-pdp-client`), and only
   * then the opaque `sub`. Keycloak's `sub` is a UUID, which matches nothing in policy data.
   */
  private Converter<Jwt, AbstractAuthenticationToken> rolesConverter() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    return jwt -> {
      Collection<GrantedAuthority> source = scopes.convert(jwt);
      Collection<GrantedAuthority> authorities = new ArrayList<>(source == null ? List.of() : source);
      Object roles = jwt.getClaim("roles");
      if (roles instanceof Collection<?> list) {
        for (Object r : list) authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
      }
      return new JwtAuthenticationToken(jwt, authorities, principalOf(jwt));
    };
  }

  private static String principalOf(Jwt jwt) {
    for (String claim : List.of("email", "preferred_username")) {
      String value = jwt.getClaimAsString(claim);
      if (value != null && !value.isBlank()) return value;
    }
    return jwt.getSubject();
  }
}
