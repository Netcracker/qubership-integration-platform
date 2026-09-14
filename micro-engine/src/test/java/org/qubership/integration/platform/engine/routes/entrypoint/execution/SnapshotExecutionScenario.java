package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotExecutionScenario {
    private final String id;
    private final String endpointUri;
    private final SnapshotScenarioDriverDefinition driver;
    private final Object body;
    private final Map<String, Object> headers;
    private final Map<String, Object> properties;
    private final Object expectedBody;
    private final boolean expectedBodyDefined;
    private final Map<String, Object> expectedHeaders;
    private final List<String> expectedAbsentHeaders;
    private final Map<String, Object> expectedExchangeHeaders;
    private final List<String> expectedAbsentExchangeHeaders;
    private final Map<String, Object> expectedProperties;
    private final List<SnapshotFixtureInteraction> interactions;
    private final List<SnapshotScenarioInvocation> invocations;

    @JsonCreator
    public SnapshotExecutionScenario(
            @JsonProperty("id") String id,
            @JsonProperty("endpointUri") String endpointUri,
            @JsonProperty("driver") SnapshotScenarioDriverDefinition driver,
            @JsonProperty("body") Object body,
            @JsonProperty("headers") Map<String, Object> headers,
            @JsonProperty("properties") Map<String, Object> properties,
            @JsonProperty("expectedBody") JsonNode expectedBody,
            @JsonProperty("expectedHeaders") Map<String, Object> expectedHeaders,
            @JsonProperty("expectedAbsentHeaders") List<String> expectedAbsentHeaders,
            @JsonProperty("expectedProperties") Map<String, Object> expectedProperties,
            @JsonProperty("interactions") List<SnapshotFixtureInteraction> interactions,
            @JsonProperty("invocations") List<SnapshotScenarioInvocation> invocations,
            @JsonProperty("expectedExchangeHeaders") Map<String, Object> expectedExchangeHeaders,
            @JsonProperty("expectedAbsentExchangeHeaders") List<String> expectedAbsentExchangeHeaders
    ) {
        this.id = requireNonBlank(id, "id");
        this.endpointUri = optionalNonBlank(endpointUri, "endpointUri");
        this.driver = driver;
        validateInvocation(this.id, this.endpointUri, this.driver);
        this.body = body;
        this.headers = immutableMapOrEmpty(headers);
        this.properties = immutableMapOrEmpty(properties);
        this.expectedBodyDefined = expectedBody != null;
        this.expectedBody = SnapshotExpectedValues.toJavaValue(expectedBody);
        this.expectedHeaders = immutableMapOrEmpty(expectedHeaders);
        this.expectedAbsentHeaders = immutableNonBlankList(expectedAbsentHeaders, "expectedAbsentHeaders");
        this.expectedExchangeHeaders = immutableMapOrEmpty(expectedExchangeHeaders);
        this.expectedAbsentExchangeHeaders = immutableNonBlankList(
                expectedAbsentExchangeHeaders, "expectedAbsentExchangeHeaders"
        );
        this.expectedProperties = immutableMapOrEmpty(expectedProperties);

        List<SnapshotFixtureInteraction> legacyInteractions =
                interactions == null ? List.of() : List.copyOf(interactions);
        this.invocations = resolveInvocations(invocations, legacyInteractions, expectedBody);
        this.interactions = legacyInteractions;
    }

    public String getId() {
        return id;
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

    public List<SnapshotScenarioInvocation> getInvocations() {
        return invocations;
    }

    private List<SnapshotScenarioInvocation> resolveInvocations(
            List<SnapshotScenarioInvocation> explicitInvocations,
            List<SnapshotFixtureInteraction> legacyInteractions,
            JsonNode expectedBody
    ) {
        if (explicitInvocations == null) {
            return List.of(new SnapshotScenarioInvocation(
                    id,
                    1,
                    null,
                    null,
                    body,
                    headers,
                    properties,
                    null,
                    expectedBody,
                    expectedHeaders,
                    expectedAbsentHeaders,
                    expectedProperties,
                    legacyInteractions,
                    expectedExchangeHeaders,
                    expectedAbsentExchangeHeaders
            ));
        }
        if (explicitInvocations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Snapshot scenario '" + id + "' invocations cannot be empty."
            );
        }
        if (hasLegacyInvocationFields(legacyInteractions)) {
            throw new IllegalArgumentException(
                    "Snapshot scenario '" + id
                            + "' cannot combine invocations with scenario-level input, expectations, or interactions."
            );
        }

        Set<String> invocationIds = new HashSet<>();
        for (SnapshotScenarioInvocation invocation : explicitInvocations) {
            if (invocation == null) {
                throw new IllegalArgumentException(
                        "Snapshot scenario '" + id + "' contains a null invocation."
                );
            }
            if (!invocationIds.add(invocation.getId())) {
                throw new IllegalArgumentException(
                        "Snapshot scenario '" + id + "' contains duplicate invocation id '"
                                + invocation.getId() + "'."
                );
            }
        }
        return List.copyOf(explicitInvocations);
    }

    private boolean hasLegacyInvocationFields(List<SnapshotFixtureInteraction> legacyInteractions) {
        return body != null
                || !headers.isEmpty()
                || !properties.isEmpty()
                || expectedBodyDefined
                || !expectedHeaders.isEmpty()
                || !expectedAbsentHeaders.isEmpty()
                || !expectedExchangeHeaders.isEmpty()
                || !expectedAbsentExchangeHeaders.isEmpty()
                || !expectedProperties.isEmpty()
                || !legacyInteractions.isEmpty();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot execution scenario " + fieldName + " is missing.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException("Snapshot execution scenario " + fieldName + " cannot be blank.");
        }
        return value;
    }

    private static void validateInvocation(
            String scenarioId,
            String endpointUri,
            SnapshotScenarioDriverDefinition driver
    ) {
        if (endpointUri == null && driver == null) {
            throw new IllegalArgumentException(
                    "Snapshot scenario '" + scenarioId + "' must define an endpointUri or a driver."
            );
        }
        if (endpointUri != null && driver != null) {
            throw new IllegalArgumentException(
                    "Snapshot scenario '" + scenarioId + "' cannot define both endpointUri and driver."
            );
        }
    }

    private static List<String> immutableNonBlankList(List<String> value, String fieldName) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        value.forEach(header -> {
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException(
                        "Snapshot execution scenario " + fieldName + " cannot contain blank values."
                );
            }
        });
        return List.copyOf(value);
    }
}
