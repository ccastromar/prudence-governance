package cc.ccmlab.prudence.config;

import cc.ccmlab.prudence.governance.Fn;
import cc.ccmlab.prudence.governance.GovernanceModel;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * ADR-000 / SSO: Prudence as an OAuth2 resource server validating the Chestertown IdP's JWTs
 * (Keycloak realm). Enabled only when an issuer is configured
 * (spring.security.oauth2.resourceserver.jwt.issuer-uri); otherwise a permit-all chain keeps dev
 * and tests unauthenticated. Realm roles arrive in the `roles` claim and become ROLE_* authorities.
 * The machine-to-machine PDP endpoint (/api/policy/decisions) is secured with the `pdp.decide`
 * scope: a caller (AOS gate, runtime adapter) presents a client-credentials token carrying that
 * scope. Granted scopes map to SCOPE_* authorities.
 */
@Configuration
public class SecurityConfig {

  @Bean
  @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")
  @Profile("!dev & !test")
  SecurityFilterChain secured(
      HttpSecurity http,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
      @Value("${prudence.identity.roles-claim:roles}") String rolesClaim,
      @Value("${prudence.identity.principal-mode:legacy}") String principalMode,
      @Value("${prudence.identity.role-mapping:}") String roleMapping,
      GovernanceModel governance)
      throws Exception {
    http.cors(c -> c.configurationSource(cors))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Each route needs a governance function (contract/governance, section 2). The caller's
        // functions come from the governance model as FN_* authorities.
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/policy-admin/**")
                    .hasAnyAuthority(
                        Fn.AUDIT, Fn.POLICY_AUTHOR, Fn.POLICY_APPROVE, Fn.PLATFORM_ADMIN)
                    .requestMatchers(HttpMethod.GET, "/internal/policy-bundles/current")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/policy-admin/drafts/*/approve")
                    .hasAuthority(Fn.POLICY_APPROVE)
                    // Publishing and rolling back are operating the platform, not approving.
                    .requestMatchers("/api/policy-admin/publications/**")
                    .hasAuthority(Fn.PLATFORM_ADMIN)
                    .requestMatchers("/api/policy-admin/**")
                    .hasAuthority(Fn.POLICY_AUTHOR)
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // The error page renders the status of a request already authorized here;
                    // guarding it again would turn a workload's 400 or 500 into a 403.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // The PDP is a machine-to-machine endpoint: an AOS gate or a runtime adapter
                    // calls it with its workload identity. runtime.decide is a machine function,
                    // which a person's token never resolves to, so humans can't drive the PDP.
                    .requestMatchers(HttpMethod.POST, "/api/policy/decisions")
                    .hasAuthority(Fn.RUNTIME_DECIDE)
                    // A runtime reports what it executed with a separate function: asking for
                    // decisions does not imply reporting executions.
                    .requestMatchers(HttpMethod.POST, "/api/executions")
                    .hasAuthority(Fn.RUNTIME_REPORT)
                    // OpenID AuthZEN front door to the same PDP. Its discovery document is public,
                    // as the specification intends.
                    .requestMatchers(
                        HttpMethod.POST, "/access/v1/evaluation", "/access/v1/evaluations")
                    .hasAuthority(Fn.RUNTIME_DECIDE)
                    .requestMatchers(HttpMethod.GET, "/.well-known/authzen-configuration")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/governance/change-assessments")
                    .hasAuthority(Fn.RUNTIME_DECIDE)
                    // Declaring a system covers its dossier: controls, cases, impact, snapshots,
                    // evidence re-drives and demonstrator runs (the browser never calls the PDP
                    // or Ernest directly: the private runner presents its workload token).
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/registry/systems",
                        "/api/registry/systems/*/submit",
                        "/api/registry/systems/*/compliance-controls/**",
                        "/api/governance/cases",
                        "/api/governance/cases/**",
                        "/api/impact/**",
                        "/api/compliance-snapshots/**",
                        "/api/evidence/**",
                        "/api/demos/**")
                    .hasAuthority(Fn.SYSTEM_DECLARE)
                    // Registering an incident and recording its reports change the register (and
                    // may suspend a system); reading it only needs a function.
                    .requestMatchers(HttpMethod.POST, "/api/incidents", "/api/incidents/**")
                    .hasAuthority(Fn.INCIDENT_HANDLE)
                    .requestMatchers(HttpMethod.POST, "/api/monitoring/**", "/api/oversight/**")
                    .hasAuthority(Fn.OVERSIGHT)
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/registry/systems/*/approve",
                        "/api/registry/systems/*/reject",
                        "/api/registry/systems/*/renew",
                        "/api/attestations")
                    .hasAuthority(Fn.SYSTEM_APPROVE)
                    // Any person function also lets its holder read Prudence.
                    .anyRequest()
                    .hasAnyAuthority(Fn.PERSON))
        .oauth2ResourceServer(
            oauth ->
                oauth.jwt(
                    jwt ->
                        jwt.jwtAuthenticationConverter(
                            rolesConverter(rolesClaim, principalMode, roleMapping, governance))));
    return http.build();
  }

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  @Profile({"dev", "test"})
  SecurityFilterChain open(
      HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
      throws Exception {
    http.cors(c -> c.configurationSource(cors))
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
      // The evidence store (ADR-001): Ernest by default, or CloudEvents on Kafka for a
      // company's own audit store. Either way evidence never travels unencrypted.
      String sink = environment.getProperty("prudence.evidence.sink", "ernest").strip();
      if ("cloudevents".equals(sink)
          && "webhook"
              .equals(
                  environment
                      .getProperty("prudence.evidence.cloudevents.transport", "kafka")
                      .strip())) {
        requireNonBlank(environment, "prudence.evidence.cloudevents.webhook-url");
        if (!environment
            .getProperty("prudence.evidence.cloudevents.webhook-url", "")
            .strip()
            .toLowerCase()
            .startsWith("https://")) {
          throw new IllegalStateException(
              "Refusing to start outside dev/test with a CloudEvents webhook that is not HTTPS");
        }
      } else if ("cloudevents".equals(sink)) {
        requireNonBlank(environment, "prudence.evidence.cloudevents.kafka-bootstrap-servers");
        String protocol =
            environment
                .getProperty("prudence.evidence.cloudevents.kafka-security-protocol", "PLAINTEXT")
                .strip()
                .toUpperCase();
        if (!protocol.equals("SSL") && !protocol.equals("SASL_SSL")) {
          throw new IllegalStateException(
              "Refusing to start outside dev/test with evidence sink cloudevents over "
                  + protocol
                  + " (use SASL_SSL or SSL)");
        }
        if (protocol.equals("SASL_SSL")) {
          requireNonBlank(environment, "prudence.evidence.cloudevents.kafka-sasl-username");
          requireNonBlank(environment, "prudence.evidence.cloudevents.kafka-sasl-password");
        }
      } else {
        requireNonBlank(environment, "prudence.ernest.base-url");
        requireNonBlank(environment, "prudence.ernest.api-key");
        if ("ingestor"
            .equals(
                environment.getProperty("prudence.ernest.decision-transport", "direct").strip())) {
          // An ingestor without a shared secret accepts anyone's events as "unverified".
          requireNonBlank(environment, "prudence.ernest.ingestor-url");
          requireNonBlank(environment, "prudence.ernest.ingestor-key");
        }
        if ("kafka"
            .equals(
                environment.getProperty("prudence.ernest.decision-transport", "direct").strip())) {
          requireNonBlank(environment, "prudence.ernest.kafka-bootstrap-servers");
          requireNonBlank(environment, "prudence.ernest.kafka-topic");
          // Decisions are governance evidence: never over an unencrypted or unauthenticated link.
          String protocol =
              environment
                  .getProperty("prudence.ernest.kafka-security-protocol", "PLAINTEXT")
                  .strip()
                  .toUpperCase();
          if (!protocol.equals("SSL") && !protocol.equals("SASL_SSL")) {
            throw new IllegalStateException(
                "Refusing to start outside dev/test with decision-transport=kafka over "
                    + protocol
                    + " (use SASL_SSL or SSL)");
          }
          if (protocol.equals("SASL_SSL")) {
            requireNonBlank(environment, "prudence.ernest.kafka-sasl-username");
            requireNonBlank(environment, "prudence.ernest.kafka-sasl-password");
          }
        }
      }
      // Require an API audience; Spring validates it on the configured JWT decoder.
      requireNonBlank(environment, "spring.security.oauth2.resourceserver.jwt.audiences");
      if (environment.getProperty("prudence.managed.enabled", Boolean.class, false)) {
        if (!"ernest".equals(environment.getProperty("prudence.evidence.sink", "ernest"))) {
          throw new IllegalStateException(
              "Managed policy audit currently requires the Ernest evidence sink");
        }
        requireNonBlank(environment, "prudence.managed.bundle-key");
        requireNonBlank(environment, "prudence.managed.opa-token");
        requireNonBlank(environment, "prudence.managed.bundle-url");
        requireNonBlank(environment, "prudence.managed.opa-instance-id");
        if (!environment.getProperty("prudence.managed.bundle-url", "").startsWith("https://")
            || !environment.getProperty("prudence.opa.base-url", "").startsWith("https://")) {
          throw new IllegalStateException(
              "Managed OPA requires HTTPS for bundle delivery and decisions");
        }
      }
      // Alerts are how humans learn about expiries, drift and lost evidence: no silent channel.
      requireNonBlank(environment, "spring.mail.host");
      requireNonBlank(environment, "prudence.alerts.governance-recipients");
      requireNonBlank(environment, "prudence.alerts.operations-recipients");
      String origins = System.getenv("PRUDENCE_CORS_ORIGINS");
      if (origins == null || origins.isBlank()) {
        throw new IllegalStateException(
            "Refusing to start outside dev/test without PRUDENCE_CORS_ORIGINS");
      }
    };
  }

  @Bean
  org.springframework.boot.ApplicationRunner requireManagedPostgres(Environment environment) {
    return ignored -> {
      if (environment.getProperty("prudence.managed.enabled", Boolean.class, false)
          && !environment.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql:")) {
        throw new IllegalStateException(
            "Managed policy publication requires PostgreSQL and Flyway migrations");
      }
      if (environment.getProperty("prudence.managed.enabled", Boolean.class, false)
          && !environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
        throw new IllegalStateException("Managed policy publication requires Flyway migrations");
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
    String origins = System.getenv("PRUDENCE_CORS_ORIGINS");
    if (origins == null || origins.isBlank()) {
      origins = "http://localhost:5200,http://127.0.0.1:5200";
    }
    List<String> allowedOrigins =
        Arrays.stream(origins.split(","))
            .map(String::strip)
            .filter(origin -> !origin.isEmpty())
            .toList();
    if (allowedOrigins.isEmpty()) {
      throw new IllegalStateException("PRUDENCE_CORS_ORIGINS must contain at least one origin");
    }
    cfg.setAllowedOrigins(allowedOrigins);
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cfg.setAllowedHeaders(List.of("*"));
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", cfg);
    return src;
  }

  /**
   * Map the configured role claim to ROLE_* authorities. Legacy principal selection preserves
   * existing OPA grants and review identities; issuer-subject is opt-in until those are migrated.
   */
  static Converter<Jwt, AbstractAuthenticationToken> rolesConverter(
      String rolesClaim, String principalMode, String roleMapping) {
    return rolesConverter(rolesClaim, principalMode, roleMapping, null);
  }

  /**
   * As above, plus the caller's governance functions in Prudence as FN_* authorities (contract v1).
   * Nothing authorizes by them yet: they ride along with the roles until each route moves to its
   * function (phase 3).
   */
  static Converter<Jwt, AbstractAuthenticationToken> rolesConverter(
      String rolesClaim, String principalMode, String roleMapping, GovernanceModel governance) {
    if (rolesClaim == null || rolesClaim.isBlank()) {
      throw new IllegalArgumentException("identity roles claim must not be blank");
    }
    if (!List.of("legacy", "issuer-subject").contains(principalMode)) {
      throw new IllegalArgumentException("unsupported identity principal mode: " + principalMode);
    }
    Map<String, String> mapping = parseRoleMapping(roleMapping);
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    return jwt -> {
      Collection<GrantedAuthority> source = scopes.convert(jwt);
      Collection<GrantedAuthority> authorities =
          new ArrayList<>(source == null ? List.of() : source);
      Object roles = jwt.getClaim(rolesClaim);
      if (roles instanceof Collection<?> list) {
        for (Object r : list) {
          if (!(r instanceof String role) || role.isBlank()) continue;
          String mapped = mapping.isEmpty() ? role : mapping.get(role);
          if (mapped != null) authorities.add(new SimpleGrantedAuthority("ROLE_" + mapped));
        }
      }
      if (governance != null) {
        for (String function : governance.resolve(jwt.getClaims(), "prudence")) {
          authorities.add(new SimpleGrantedAuthority("FN_" + function));
        }
      }
      return new JwtAuthenticationToken(jwt, authorities, principalOf(jwt, principalMode));
    };
  }

  // External roles are trusted only from the configured issuer. When a mapping is configured,
  // unlisted roles never become authorities; a blank mapping preserves the legacy deployment.
  static Map<String, String> parseRoleMapping(String configured) {
    Map<String, String> mapping = new LinkedHashMap<>();
    if (configured == null || configured.isBlank()) return Map.of();
    for (String entry : configured.split(",", -1)) {
      String[] pair = entry.strip().split("=", -1);
      if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
        throw new IllegalArgumentException("invalid identity role mapping");
      }
      if (mapping.putIfAbsent(pair[0].strip(), pair[1].strip()) != null) {
        throw new IllegalArgumentException("duplicate identity role mapping");
      }
    }
    return Map.copyOf(mapping);
  }

  /// Fail-closed: a token that names no one is rejected (401) rather than accepted as an
  /// anonymous actor — otherwise reviews would be signed by "" and the four-eyes and
  /// self-approval checks would compare empty names.
  private static String principalOf(Jwt jwt, String principalMode) {
    if ("issuer-subject".equals(principalMode)) {
      String issuer = jwt.getClaimAsString("iss");
      String subject = jwt.getSubject();
      if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
        throw new InvalidBearerTokenException("token must carry issuer and subject");
      }
      // Length prefix keeps the pair unambiguous without assuming a separator cannot occur.
      String identity = issuer.length() + ":" + issuer + subject;
      if (identity.length() > 512)
        throw new InvalidBearerTokenException("issuer and subject exceed identity limit");
      return identity;
    }
    for (String claim : List.of("email", "preferred_username", "sub")) {
      String value = jwt.getClaimAsString(claim);
      if (value != null && !value.isBlank()) return value;
    }
    throw new InvalidBearerTokenException(
        "token carries no identity (email, preferred_username or sub)");
  }
}
