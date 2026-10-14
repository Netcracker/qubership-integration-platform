package org.qubership.integration.platform.engine.routes.fixture;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

final class SnapshotEndpointParameters {
    private SnapshotEndpointParameters() {
    }

    static Map<String, String> parse(
            String query,
            Function<String, IllegalArgumentException> duplicateParameterException
    ) {
        if (query.isEmpty()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            String value = separator < 0 ? "" : parameter.substring(separator + 1);
            if (parameters.putIfAbsent(name, value) != null) {
                throw duplicateParameterException.apply(name);
            }
        }
        return Collections.unmodifiableMap(parameters);
    }
}
