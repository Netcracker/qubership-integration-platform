package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SnapshotFailureExpectation {
    private static final List<String> REQUIRED_FIELDS = List.of("type", "message", "cause");
    private static final Set<String> SUPPORTED_FIELDS = Set.copyOf(REQUIRED_FIELDS);

    private final String type;
    private final String message;
    private final SnapshotFailureExpectation cause;

    private SnapshotFailureExpectation(
            String type,
            String message,
            SnapshotFailureExpectation cause
    ) {
        this.type = type;
        this.message = message;
        this.cause = cause;
    }

    static SnapshotFailureExpectation fromManifestValue(JsonNode value, String invocationId) {
        if (value == null) {
            return null;
        }
        return parse(value, "Snapshot scenario invocation '" + invocationId + "' expectedFailure");
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public SnapshotFailureExpectation getCause() {
        return cause;
    }

    private static SnapshotFailureExpectation parse(JsonNode value, String path) {
        if (!value.isObject()) {
            throw invalid(path, "must be an object with required fields type, message, and cause");
        }

        Set<String> configuredFields = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(configuredFields::add);
        for (String configuredField : configuredFields) {
            if (!SUPPORTED_FIELDS.contains(configuredField)) {
                throw invalid(path, "contains unsupported field '" + configuredField + "'");
            }
        }
        for (String requiredField : REQUIRED_FIELDS) {
            if (!value.has(requiredField)) {
                throw invalid(path, "is missing required field '" + requiredField + "'");
            }
        }

        JsonNode typeNode = value.get("type");
        if (!typeNode.isTextual() || typeNode.textValue().isBlank()) {
            throw invalid(path + ".type", "must be a nonblank string");
        }

        JsonNode messageNode = value.get("message");
        if (!messageNode.isNull() && !messageNode.isTextual()) {
            throw invalid(path + ".message", "must be a string or null");
        }

        JsonNode causeNode = value.get("cause");
        SnapshotFailureExpectation cause = causeNode.isNull()
                ? null
                : parse(causeNode, path + ".cause");
        return new SnapshotFailureExpectation(
                typeNode.textValue(),
                messageNode.isNull() ? null : messageNode.textValue(),
                cause
        );
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason + ".");
    }
}
