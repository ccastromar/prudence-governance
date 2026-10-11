package com.prudence.posture.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.posture.application.port.out.EvidenceHealthPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/// ACL adapter over Ernest: reads GET /api/verify and reports whether the hashchain is intact
/// and reachable. Fail-closed — if Ernest cannot be reached or does not confirm validity,
/// evidence is reported as unhealthy rather than optimistically green.
@Component
public class ErnestEvidenceHealthAdapter implements EvidenceHealthPort {

    private static final Logger log = LoggerFactory.getLogger(ErnestEvidenceHealthAdapter.class);

    private final RestClient client;

    public ErnestEvidenceHealthAdapter(IntegrationProperties props) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.ernest().baseUrl())
                .requestFactory(timeoutFactory());
        String apiKey = props.ernest().apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            // Prudence authenticates to Ernest as a read client (ADR-002 caller identity).
            builder.defaultHeader("X-Ernest-Api-Key", apiKey);
        }
        this.client = builder.build();
    }

    @Override
    public EvidenceHealth check() {
        try {
            Map<?, ?> body = client.get().uri("/api/verify").retrieve().body(Map.class);
            boolean valid = body != null && Boolean.TRUE.equals(body.get("isValid"));
            Object blocks = body != null ? body.get("blocksVerified") : null;
            if (valid) {
                return new EvidenceHealth(true, "Hashchain verified" + (blocks != null ? " (" + blocks + " blocks)" : "") + ".");
            }
            return new EvidenceHealth(false, "Ernest reports the hashchain is not valid.");
        } catch (Exception e) {
            log.warn("could not read evidence health from Ernest: {}", e.getMessage());
            return new EvidenceHealth(false, "Ernest evidence layer is unreachable.");
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        return f;
    }
}
