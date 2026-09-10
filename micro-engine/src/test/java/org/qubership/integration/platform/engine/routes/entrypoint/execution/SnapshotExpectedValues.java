package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SnapshotExpectedValues {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SnapshotExpectedValues() {
    }

    static Object toJavaValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(value, Object.class);
    }
}
