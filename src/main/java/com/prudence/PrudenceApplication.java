package com.prudence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/// Prudence — the governance vertex. AOS orchestrates, Ernest proves, Prudence governs.
@SpringBootApplication
@ConfigurationPropertiesScan
public class PrudenceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrudenceApplication.class, args);
    }
}
