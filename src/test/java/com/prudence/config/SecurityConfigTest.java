package com.prudence.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

    @Test
    void productionRefusesKafkaOverPlaintext() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("prudence.ernest.kafka-security-protocol", "PLAINTEXT");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> run(environment));

        assertTrue(failure.getMessage().contains("SASL_SSL or SSL"));
    }

    @Test
    void productionRequiresSaslCredentialsForKafka() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("prudence.ernest.kafka-security-protocol", "SASL_SSL");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> run(environment));

        assertTrue(failure.getMessage().contains("kafka-sasl-username"));
    }

    private static MockEnvironment productionEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://idp.example.eu/realms/prudence")
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
