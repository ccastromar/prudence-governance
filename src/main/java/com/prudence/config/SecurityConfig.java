package com.prudence.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;

/**
 * ADR-000 / SSO: Prudence as an OAuth2 resource server validating the Chestertown IdP's JWTs
 * (Keycloak realm). Enabled only when an issuer is configured
 * (spring.security.oauth2.resourceserver.jwt.issuer-uri); otherwise a permit-all chain keeps
 * dev and tests unauthenticated. Realm roles arrive in the `roles` claim and become
 * ROLE_* authorities. The machine-to-machine PDP endpoint stays open (a gate like AOS calls
 * it; securing it with client credentials is a separate concern).
 */
@Configuration
public class SecurityConfig {

  @Bean
  @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")
  SecurityFilterChain secured(HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
    http
        .cors(c -> c.configurationSource(cors))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/api/policy/decisions").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())));
    return http.build();
  }

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  SecurityFilterChain open(HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
    http
        .cors(c -> c.configurationSource(cors))
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
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

  /** Map Keycloak realm roles (the `roles` claim) to ROLE_* authorities. */
  private JwtAuthenticationConverter rolesConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
      Collection<GrantedAuthority> authorities = new JwtGrantedAuthoritiesConverter().convert(jwt);
      Object roles = jwt.getClaim("roles");
      if (roles instanceof Collection<?> list) {
        for (Object r : list) authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
      }
      return authorities;
    });
    return converter;
  }
}
