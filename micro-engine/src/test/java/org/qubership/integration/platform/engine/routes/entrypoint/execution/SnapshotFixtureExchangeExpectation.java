package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotFixtureExchangeExpectation {
    private final int count;
    private final Object body;
    private final boolean bodyDefined;
    private final Map<String, Object> headers;
    private final Map<String, Object> properties;

    @JsonCreator
    public SnapshotFixtureExchangeExpectation(
            @JsonProperty("count") Integer count,
            @JsonProperty("body") JsonNode body,
            @JsonProperty("headers") Map<String, Object> headers,
            @JsonProperty("properties") Map<String, Object> properties
    ) {
        this.count = count == null ? 1 : requireNonNegative(count);
        this.bodyDefined = body != null;
        this.body = SnapshotExpectedValues.toJavaValue(body);
        this.headers = immutableMapOrEmpty(headers);
        this.properties = immutableMapOrEmpty(properties);
    }

    public int getCount() {
        return count;
    }

    public Object getBody() {
        return body;
    }

    public boolean hasBody() {
        return bodyDefined;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    private static int requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Snapshot fixture expected exchange count cannot be negative."
            );
        }
        return value;
    }

}
