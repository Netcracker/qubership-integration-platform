package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class TypedHeadersSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> PARAMETERS = Set.of("endpointUri", "headerTypes");
    private static final Set<String> HEADER_TYPES = Set.of("bytes", "integer", "long", "double", "date");

    @Override
    public String getId() {
        return "typedHeaders";
    }

    @Override
    public SnapshotScenarioDriver create(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations
    ) {
        Map<String, Object> parameters = definition.getParameters();
        for (String parameter : parameters.keySet()) {
            if (!PARAMETERS.contains(parameter)) {
                throw new IllegalArgumentException("Typed headers driver has unknown parameter '" + parameter + "'.");
            }
        }
        if (!(parameters.get("endpointUri") instanceof String endpointUri) || endpointUri.isBlank()) {
            throw new IllegalArgumentException("Typed headers driver endpointUri must be a nonblank string.");
        }
        if (!(parameters.get("headerTypes") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Typed headers driver headerTypes must be a map.");
        }
        Map<String, String> headerTypes = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String header) || header.isBlank()) {
                throw new IllegalArgumentException("Typed headers driver headerTypes keys must be nonblank strings.");
            }
            if (!(value instanceof String type) || !HEADER_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "Typed headers driver type for header '" + header + "' must be bytes, integer, long, double, or date."
                );
            }
            headerTypes.put(header, type);
        });
        return new TypedHeadersSnapshotScenarioDriver(endpointUri, Map.copyOf(headerTypes));
    }

    private record TypedHeadersSnapshotScenarioDriver(
            String endpointUri,
            Map<String, String> headerTypes
    ) implements SnapshotScenarioDriver {
        @Override
        public Exchange execute(ProducerTemplate producerTemplate, SnapshotScenarioInvocation invocation) {
            Map<String, Object> headers = new LinkedHashMap<>(invocation.getHeaders());
            headerTypes.forEach((header, type) -> {
                if (headers.containsKey(header)) {
                    headers.put(header, convertHeader(headers.get(header), type, header));
                }
            });
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(invocation.getBody());
                headers.forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }

        private static Object convertHeader(Object value, String type, String header) {
            if (value == null) {
                return null;
            }
            if ("bytes".equals(type)) {
                if (!(value instanceof String string)) {
                    throw new IllegalArgumentException("Typed headers driver header '" + header + "' must be a hex string.");
                }
                try {
                    return HexFormat.of().parseHex(string);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "Typed headers driver header '" + header + "' contains invalid hex.", exception
                    );
                }
            }
            try {
                return switch (type) {
                    case "integer" -> Integer.valueOf(value.toString());
                    case "long" -> Long.valueOf(value.toString());
                    case "double" -> Double.valueOf(value.toString());
                    case "date" -> new Date(Long.parseLong(value.toString()));
                    default -> throw new IllegalArgumentException("Unsupported header type '" + type + "'.");
                };
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Typed headers driver header '" + header + "' cannot be represented as " + type + ".", exception
                );
            }
        }
    }
}
