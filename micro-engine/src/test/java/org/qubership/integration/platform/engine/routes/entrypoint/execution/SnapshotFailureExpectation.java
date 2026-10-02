package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SnapshotFailureExpectation {
    private static final List<String> REQUIRED_FIELDS = List.of("type", "cause");
    private static final Set<String> SUPPORTED_FIELDS = Set.of("type", "message", "ignoreMessage", "cause");

    private final String type;
    private final String message;
    private final SnapshotFailureExpectation cause;
    private final boolean messageIgnored;
    private final List<SnapshotFailureExpectation> alternatives;

    private SnapshotFailureExpectation(
            String type,
            String message,
            SnapshotFailureExpectation cause,
            boolean messageIgnored,
            List<SnapshotFailureExpectation> alternatives
    ) {
        this.type = type;
        this.message = message;
        this.cause = cause;
        this.messageIgnored = messageIgnored;
        this.alternatives = List.copyOf(alternatives);
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

    public boolean isMessageIgnored() {
        return messageIgnored;
    }

    public List<SnapshotFailureExpectation> getAlternatives() {
        return alternatives;
    }

    static SnapshotFailureExpectation parse(JsonNode value, String path) {
        if (!value.isObject()) {
            throw invalid(path, "must be a failure object or an object containing only anyOf");
        }
        if (value.has("anyOf")) {
            return parseAlternatives(value, path);
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

        if (value.has("message") == value.has("ignoreMessage")) {
            throw invalid(path, "must define exactly one of message or ignoreMessage");
        }
        boolean messageIgnored = value.has("ignoreMessage");
        if (messageIgnored && (!value.get("ignoreMessage").isBoolean() || !value.get("ignoreMessage").booleanValue())) {
            throw invalid(path + ".ignoreMessage", "must be true");
        }
        JsonNode messageNode = value.get("message");
        if (messageNode != null && !messageNode.isNull() && !messageNode.isTextual()) {
            throw invalid(path + ".message", "must be a string or null");
        }

        JsonNode causeNode = value.get("cause");
        SnapshotFailureExpectation cause = causeNode.isNull()
                ? null
                : parse(causeNode, path + ".cause");
        return new SnapshotFailureExpectation(
                typeNode.textValue(),
                messageNode == null || messageNode.isNull() ? null : messageNode.textValue(),
                cause,
                messageIgnored,
                List.of()
        );
    }

    private static SnapshotFailureExpectation parseAlternatives(JsonNode value, String path) {
        if (value.size() != 1) {
            throw invalid(path, "must not combine anyOf with other fields");
        }
        JsonNode alternativesNode = value.get("anyOf");
        if (!alternativesNode.isArray() || alternativesNode.isEmpty()) {
            throw invalid(path + ".anyOf", "must be a nonempty array of failure objects");
        }
        List<SnapshotFailureExpectation> alternatives = new ArrayList<>();
        for (int index = 0; index < alternativesNode.size(); index++) {
            alternatives.add(parse(alternativesNode.get(index), path + ".anyOf[" + index + "]"));
        }
        return new SnapshotFailureExpectation(null, null, null, false, alternatives);
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason + ".");
    }
}
