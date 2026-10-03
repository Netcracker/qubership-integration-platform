package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotScenarioInvocation {
    private final String id;
    private final int repeat;
    private final String endpointUri;
    private final SnapshotScenarioDriverDefinition driver;
    private final Object body;
    private final Map<String, Object> headers;
    private final Map<String, Object> properties;
    private final SnapshotFailureExpectation expectedFailure;
    private final Object expectedBody;
    private final boolean expectedBodyDefined;
    private final Map<String, Object> expectedHeaders;
    private final List<String> expectedAbsentHeaders;
    private final Map<String, Object> expectedExchangeHeaders;
    private final List<String> expectedAbsentExchangeHeaders;
    private final Map<String, Object> expectedProperties;
    private final List<SnapshotFixtureInteraction> interactions;

    @JsonCreator
    public SnapshotScenarioInvocation(
            @JsonProperty("id") String id,
            @JsonProperty("repeat") Integer repeat,
            @JsonProperty("endpointUri") String endpointUri,
            @JsonProperty("driver") SnapshotScenarioDriverDefinition driver,
            @JsonProperty("body") Object body,
            @JsonProperty("headers") Map<String, Object> headers,
            @JsonProperty("properties") Map<String, Object> properties,
            @JsonProperty("expectedFailure") JsonNode expectedFailure,
            @JsonProperty("expectedBody") JsonNode expectedBody,
            @JsonProperty("expectedHeaders") Map<String, Object> expectedHeaders,
            @JsonProperty("expectedAbsentHeaders") List<String> expectedAbsentHeaders,
            @JsonProperty("expectedProperties") Map<String, Object> expectedProperties,
            @JsonProperty("interactions") List<SnapshotFixtureInteraction> interactions,
            @JsonProperty("expectedExchangeHeaders") Map<String, Object> expectedExchangeHeaders,
            @JsonProperty("expectedAbsentExchangeHeaders") List<String> expectedAbsentExchangeHeaders
    ) {
        this.id = requireNonBlank(id);
        this.repeat = repeat == null ? 1 : repeat;
        if (this.repeat <= 0) {
            throw new IllegalArgumentException(
                    "Snapshot scenario invocation '" + this.id + "' repeat must be greater than zero."
            );
        }
        this.endpointUri = optionalNonBlank(endpointUri, "endpointUri");
        this.driver = driver;
        if (this.endpointUri != null && this.driver != null) {
            throw new IllegalArgumentException(
                    "Snapshot scenario invocation '" + this.id
                            + "' cannot define both endpointUri and driver."
            );
        }
        this.body = body;
        this.headers = immutableMapOrEmpty(headers);
        this.properties = immutableMapOrEmpty(properties);
        this.expectedFailure = SnapshotFailureExpectation.fromManifestValue(expectedFailure, this.id);
        this.expectedBodyDefined = expectedBody != null;
        this.expectedBody = SnapshotExpectedValues.toJavaValue(expectedBody);
        this.expectedHeaders = immutableMapOrEmpty(expectedHeaders);
        this.expectedAbsentHeaders = immutableNonBlankList(expectedAbsentHeaders, "expectedAbsentHeaders");
        this.expectedExchangeHeaders = immutableMapOrEmpty(expectedExchangeHeaders);
        this.expectedAbsentExchangeHeaders = immutableNonBlankList(
                expectedAbsentExchangeHeaders, "expectedAbsentExchangeHeaders"
        );
        this.expectedProperties = immutableMapOrEmpty(expectedProperties);
        this.interactions = interactions == null ? List.of() : List.copyOf(interactions);
    }

    public String getId() {
        return id;
    }

    public int getRepeat() {
        return repeat;
    }

    public String getEndpointUri() {
        return endpointUri;
    }

    public SnapshotScenarioDriverDefinition getDriver() {
        return driver;
    }

    public Object getBody() {
        return body;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public boolean isExpectedFailure() {
        return expectedFailure != null;
    }

    public SnapshotFailureExpectation getExpectedFailure() {
        return expectedFailure;
    }

    public Object getExpectedBody() {
        return expectedBody;
    }

    public boolean hasExpectedBody() {
        return expectedBodyDefined;
    }

    public Map<String, Object> getExpectedHeaders() {
        return expectedHeaders;
    }

    public List<String> getExpectedAbsentHeaders() {
        return expectedAbsentHeaders;
    }

    public Map<String, Object> getExpectedExchangeHeaders() {
        return expectedExchangeHeaders;
    }

    public List<String> getExpectedAbsentExchangeHeaders() {
        return expectedAbsentExchangeHeaders;
    }

    public Map<String, Object> getExpectedProperties() {
        return expectedProperties;
    }

    public List<SnapshotFixtureInteraction> getInteractions() {
        return interactions;
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot scenario invocation id is missing.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "Snapshot scenario invocation " + fieldName + " cannot be blank."
            );
        }
        return value;
    }

    private static List<String> immutableNonBlankList(List<String> value, String fieldName) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        value.forEach(header -> {
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException(
                        "Snapshot scenario invocation " + fieldName + " cannot contain blank values."
                );
            }
        });
        return List.copyOf(value);
    }
}
