package org.qubership.integration.platform.runtime.catalog.service.qcp;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpSnapshotClientResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class QcpCallbackClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 5000L;

    private final RestTemplate restTemplate;

    public QcpCallbackClient(@Qualifier("restTemplateMS") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendCallback(String snapshotId, String callbackUrl, QcpSnapshotClientResponse response) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("No callback URL provided for snapshotId={} — skipping PATCH (status={})", snapshotId, response.getStatus());
            return;
        }

        log.info(
                "Sending PATCH callback: url={} status={} clientId={} namespace={}",
                callbackUrl,
                response.getStatus(),
                response.getClientId(),
                response.getNamespace()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<QcpSnapshotClientResponse> entity = new HttpEntity<>(response, headers);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Callback attempt {}/{} started", attempt, MAX_ATTEMPTS);
                ResponseEntity<Void> callbackResponse = restTemplate.exchange(
                        callbackUrl,
                        HttpMethod.PATCH,
                        entity,
                        Void.class
                );
                log.info(
                        "Callback attempt {}/{} succeeded: httpStatus={}",
                        attempt,
                        MAX_ATTEMPTS,
                        callbackResponse.getStatusCode().value()
                );
                return;
            } catch (RestClientException ex) {
                log.warn(
                        "Callback attempt {}/{} failed: {} — {}",
                        attempt,
                        MAX_ATTEMPTS,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        log.warn("Callback retry sleep interrupted");
                    }
                }
            }
        }
        log.error(
                "Callback FAILED after {} attempts to callbackUrl={}",
                MAX_ATTEMPTS,
                callbackUrl
        );
    }
}
