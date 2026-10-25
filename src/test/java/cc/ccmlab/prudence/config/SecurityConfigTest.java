package cc.ccmlab.prudence.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.governance.GovernanceModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {

  @Test
  void productionRefusesKafkaOverPlaintext() {
    MockEnvironment environment =
        productionEnvironment()
            .withProperty("prudence.ernest.kafka-security-protocol", "PLAINTEXT");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> run(environment));

    assertTrue(failure.getMessage().contains("SASL_SSL or SSL"));
  }

  @Test
  void productionRequiresSaslCredentialsForKafka() {
    MockEnvironment environment =
        productionEnvironment().withProperty("prudence.ernest.kafka-security-protocol", "SASL_SSL");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> run(environment));

    assertTrue(failure.getMessage().contains("kafka-sasl-username"));
  }

  @Test
  void tokensBecomeGovernanceFunctionAuthorities() throws Exception {
    var model =
        GovernanceModel.load(
            new ClassPathResource("governance/default-model.yaml").getInputStream());
    var converter = SecurityConfig.rolesConverter("roles", "legacy", "", model);

    var person =
        converter.convert(
            jwt(Map.of("sub", "alice", "roles", List.of("prudence-author", "aos-operator"))));
    assertTrue(has(person, "FN_system.declare"));
    assertTrue(!has(person, "FN_runtime.decide"));
    assertTrue(
        person.getAuthorities().stream().noneMatch(a -> a.getAuthority().startsWith("FN_ai.use")));

    var workload = converter.convert(jwt(Map.of("sub", "svc", "scope", "pdp.decide")));
    assertTrue(has(workload, "FN_runtime.decide"));

    // A person cannot reach a machine function through a group named like a scope.
    var impostor = converter.convert(jwt(Map.of("sub", "mallory", "roles", List.of("pdp.decide"))));
    assertTrue(
        impostor.getAuthorities().stream().noneMatch(a -> a.getAuthority().startsWith("FN_")));
  }

  private static boolean has(Authentication auth, String authority) {
    return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return Jwt.withTokenValue("t").header("alg", "none").claims(c -> c.putAll(claims)).build();
  }

  private static MockEnvironment productionEnvironment() {
    return new MockEnvironment()
        .withProperty(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "https://idp.example.eu/realms/prudence")
        .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.eu/prudence")
        .withProperty("spring.datasource.username", "prudence")
        .withProperty("spring.datasource.password", "secret")
        .withProperty("prudence.ernest.base-url", "https://ernest.example.eu")
        .withProperty("prudence.ernest.api-key", "secret")
        .withProperty("prudence.ernest.decision-transport", "kafka")
        .withProperty("prudence.ernest.kafka-bootstrap-servers", "kafka.example.eu:9093")
        .withProperty("prudence.ernest.kafka-topic", "prudence.decisions");
  }

  private static void run(MockEnvironment environment) throws Exception {
    ApplicationRunner runner = new SecurityConfig().requireProductionSecurity(environment);
    runner.run(null);
  }
}
