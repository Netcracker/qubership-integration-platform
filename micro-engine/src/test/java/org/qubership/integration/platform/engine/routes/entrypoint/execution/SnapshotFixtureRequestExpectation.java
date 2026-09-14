package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotFixtureRequestExpectation {
    private final int count;
    private final String method;
    private final String path;
    private final String query;
    private final String destination;
    private final String key;
    private final Object body;
    private final boolean bodyDefined;
    private final Map<String, Object> headers;
    private final Map<String, Object> properties;

    @JsonCreator
    public SnapshotFixtureRequestExpectation(
            @JsonProperty("count") Integer count,
            @JsonProperty("method") String method,
            @JsonProperty("path") String path,
            @JsonProperty("query") String query,
            @JsonProperty("destination") String destination,
            @JsonProperty("key") String key,
            @JsonProperty("body") JsonNode body,
            @JsonProperty("headers") Map<String, Object> headers,
            @JsonProperty("properties") Map<String, Object> properties
    ) {
        this.count = count == null ? 1 : requireNonNegative(count);
        this.method = optionalNonBlank(method, "method");
        this.path = optionalNonBlank(path, "path");
        this.query = optionalNonBlank(query, "query");
        this.destination = optionalNonBlank(destination, "destination");
        this.key = optionalNonBlank(key, "key");
        this.bodyDefined = body != null;
        this.body = SnapshotExpectedValues.toJavaValue(body);
        this.headers = immutableMapOrEmpty(headers);
        this.properties = immutableMapOrEmpty(properties);
    }

    public int getCount() {
        return count;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public String getDestination() {
        return destination;
    }

    public String getKey() {
        return key;
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
            throw new IllegalArgumentException("Snapshot fixture expected request count cannot be negative.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture expected request " + fieldName + " cannot be blank."
            );
        }
        return value;
    }

}
