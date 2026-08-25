package org.qubership.integration.platform.engine.service.testing;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Identifies a mocked call for the testing service. The header name, the field names and the standard, padded
 * base64 alphabet are a contract with the Go service, which parses {@code path} and {@code operationPath} to
 * feed its query- and path-parameter matchers.
 */
@JsonPropertyOrder({"chainId", "elementId", "operationPath", "path"})
public record TestingContext(String chainId, String elementId, String operationPath, String path) {

    public static final String HEADER_NAME = "Testing-Service-Context";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String encode() {
        try {
            byte[] json = MAPPER.writeValueAsString(this).getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to encode the testing service context", exception);
        }
    }
}
