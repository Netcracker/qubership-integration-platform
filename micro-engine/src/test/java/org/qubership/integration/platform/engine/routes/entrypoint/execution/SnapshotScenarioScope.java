package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SnapshotScenarioScope {
    LONG("Long"),
    SHORT("Short");

    private final String value;

    SnapshotScenarioScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SnapshotScenarioScope fromValue(String value) {
        return switch (value) {
            case "Long" -> LONG;
            case "Short" -> SHORT;
            case null, default -> throw new IllegalArgumentException("Snapshot scenario scope must be Long or Short.");
        };
    }
}
