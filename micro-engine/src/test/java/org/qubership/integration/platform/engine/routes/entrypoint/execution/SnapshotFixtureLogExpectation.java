package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SnapshotFixtureLogExpectation {
    private static final Set<String> SUPPORTED_LEVELS = Set.of("ERROR", "INFO", "WARN");

    private final int count;
    private final String level;
    private final String message;
    private final Map<String, String> mdc;

    @JsonCreator
    public SnapshotFixtureLogExpectation(
            @JsonProperty("count") Integer count,
            @JsonProperty("level") String level,
            @JsonProperty("message") String message,
            @JsonProperty("mdc") Map<String, String> mdc
    ) {
        this.count = count == null ? 1 : requireNonNegative(count);
        this.level = requireSupportedLevel(level);
        this.message = requireMessage(message);
        this.mdc = immutableMdc(mdc);
    }

    public int getCount() {
        return count;
    }

    public String getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getMdc() {
        return mdc;
    }

    private static int requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Snapshot fixture expected log count cannot be negative.");
        }
        return value;
    }

    private static String requireSupportedLevel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot fixture expected log level is missing.");
        }
        String normalizedValue = value.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_LEVELS.contains(normalizedValue)) {
            throw new IllegalArgumentException(
                    "Snapshot fixture expected log level must be ERROR, INFO, or WARN."
            );
        }
        return normalizedValue;
    }

    private static String requireMessage(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Snapshot fixture expected log message is missing.");
        }
        return value;
    }

    private static Map<String, String> immutableMdc(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        value.forEach((key, entryValue) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Snapshot fixture expected log MDC key is missing.");
            }
            if (entryValue == null) {
                throw new IllegalArgumentException(
                        "Snapshot fixture expected log MDC value for key '" + key + "' is missing."
                );
            }
            result.put(key, entryValue);
        });
        return Collections.unmodifiableMap(result);
    }
}
