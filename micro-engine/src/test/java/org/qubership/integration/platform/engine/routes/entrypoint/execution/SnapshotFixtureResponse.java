package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotFixtureResponse {
    private final int status;
    private final boolean statusDefined;
    private final Object body;
    private final Map<String, Object> headers;
    private final Map<String, Object> properties;

    @JsonCreator
    public SnapshotFixtureResponse(
            @JsonProperty("status") Integer status,
            @JsonProperty("body") Object body,
            @JsonProperty("headers") Map<String, Object> headers,
            @JsonProperty("properties") Map<String, Object> properties
    ) {
        this.statusDefined = status != null;
        this.status = status == null ? 200 : requireHttpStatus(status);
        this.body = body;
        this.headers = immutableMapOrEmpty(headers);
        this.properties = immutableMapOrEmpty(properties);
    }

    public int getStatus() {
        return status;
    }

    public boolean hasExplicitStatus() {
        return statusDefined;
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

    private static int requireHttpStatus(int value) {
        if (value < 100 || value > 599) {
            throw new IllegalArgumentException("Snapshot fixture response status must be between 100 and 599.");
        }
        return value;
    }

}
