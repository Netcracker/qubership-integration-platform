package org.qubership.integration.platform.engine.routes.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SnapshotCollections {
    private SnapshotCollections() {
    }

    public static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static <K, V> Map<K, V> immutableMapOrEmpty(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return immutableMap(values);
    }
}
