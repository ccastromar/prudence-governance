package com.prudence.evidence.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.delivery.DeliveryFailure;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/// The explicit failure contract Prudence applies to every Ernest write. Shared by the
/// decision, review and attestation adapters so they all classify a response the same way.
public final class ErnestHttp {

    /// Bounded well below the outbox lease, so a hung call can never outlive its claim.
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private ErnestHttp() {}

    /// A RestClient for Ernest with timeouts and the X-Ernest-Api-Key credential.
    public static RestClient client(IntegrationProperties props) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(CONNECT_TIMEOUT);
        timeouts.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder b = RestClient.builder().baseUrl(props.ernest().baseUrl()).requestFactory(timeouts);
        String apiKey = props.ernest().apiKey();
        if (apiKey != null && !apiKey.isBlank()) b.defaultHeader("X-Ernest-Api-Key", apiKey);
        return b.build();
    }

    /// A RestClient for Ernest's event-ingestor, with the same timeouts and its own credential.
    public static RestClient ingestorClient(String ingestorUrl, String ingestorKey) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(CONNECT_TIMEOUT);
        timeouts.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder b = RestClient.builder().baseUrl(ingestorUrl).requestFactory(timeouts);
        if (ingestorKey != null && !ingestorKey.isBlank()) b.defaultHeader("X-Ernest-Ingest-Key", ingestorKey);
        return b.build();
    }

    /// Maps an Ernest write error onto the delivery contract:
    /// 401/403 or not configured → CONFIGURATION; 408/409/429, 5xx, I/O → RETRYABLE;
    /// any other 4xx (e.g. invalid signature) → PERMANENT.
    public static DeliveryFailure classify(Throwable error) {
        if (error instanceof RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) return DeliveryFailure.CONFIGURATION;
            if (status == 408 || status == 409 || status == 429 || status >= 500) return DeliveryFailure.RETRYABLE;
            return status >= 400 ? DeliveryFailure.PERMANENT : DeliveryFailure.RETRYABLE;
        }
        if (error instanceof ResourceAccessException) return DeliveryFailure.RETRYABLE; // I/O, timeout
        if (error instanceof IllegalStateException) return DeliveryFailure.CONFIGURATION; // not configured
        return DeliveryFailure.RETRYABLE;
    }

    /// True only when Ernest says the model is already registered. Ernest currently answers a
    /// duplicate with 400 "... already exists." (409 is accepted for when it adopts it); every
    /// other 400 — a bad signature, a malformed body — is a real rejection and must surface.
    public static boolean isAlreadyRegistered(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 409) return true;
        return status == 400 && e.getResponseBodyAsString().contains("already exists");
    }
}
