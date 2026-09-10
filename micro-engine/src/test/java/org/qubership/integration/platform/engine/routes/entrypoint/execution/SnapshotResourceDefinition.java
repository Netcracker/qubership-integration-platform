package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SnapshotResourceDefinition {
    private final String source;
    private final String target;

    @JsonCreator
    public SnapshotResourceDefinition(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target
    ) {
        this.source = requireNonBlank(source, "source");
        this.target = requireNonBlank(target, "target");
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot resource definition " + fieldName + " is missing.");
        }
        return value;
    }
}
