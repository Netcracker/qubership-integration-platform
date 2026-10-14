package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class NumericInputSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> PARAMETERS = Set.of("endpointUri", "bodyType", "headerTypes");
    private static final Set<String> SCALAR_TYPES = Set.of("long", "integer");

    @Override
    public String getId() {
        return "numericInput";
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
                throw new IllegalArgumentException("Numeric input driver has unknown parameter '" + parameter + "'.");
            }
        }
        String endpointUri = requiredString(parameters, "endpointUri");
        String bodyType = requiredString(parameters, "bodyType");
        if (!SCALAR_TYPES.contains(bodyType) && !"long-list".equals(bodyType)) {
            throw new IllegalArgumentException("Numeric input driver bodyType must be long, integer, or long-list.");
        }
        Map<String, String> headerTypes = headerTypes(parameters);
        return new NumericInputSnapshotScenarioDriver(endpointUri, bodyType, headerTypes);
    }

    private static String requiredString(Map<String, Object> parameters, String parameter) {
        Object value = parameters.get(parameter);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Numeric input driver parameter '" + parameter + "' must be a nonblank string.");
        }
        return string;
    }

    private static Map<String, String> headerTypes(Map<String, Object> parameters) {
        if (!parameters.containsKey("headerTypes")) {
            return Map.of();
        }
        if (!(parameters.get("headerTypes") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Numeric input driver headerTypes must be a map.");
        }
        Map<String, String> types = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String header) || header.isBlank()) {
                throw new IllegalArgumentException("Numeric input driver headerTypes keys must be nonblank strings.");
            }
            if (!(value instanceof String type) || !SCALAR_TYPES.contains(type)) {
                throw new IllegalArgumentException("Numeric input driver type for header '" + header + "' must be long or integer.");
            }
            types.put(header, type);
        });
        return Map.copyOf(types);
    }

    private record NumericInputSnapshotScenarioDriver(
            String endpointUri,
            String bodyType,
            Map<String, String> headerTypes
    ) implements SnapshotScenarioDriver {
        @Override
        public Exchange execute(ProducerTemplate producerTemplate, SnapshotScenarioInvocation invocation) {
            Object body = convertBody(invocation.getBody());
            Map<String, Object> headers = new LinkedHashMap<>(invocation.getHeaders());
            headerTypes.forEach((header, type) -> {
                if (headers.containsKey(header)) {
                    headers.put(header, convertScalar(headers.get(header), type, "header '" + header + "'"));
                }
            });
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(body);
                headers.forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }

        private Object convertBody(Object value) {
            if (!"long-list".equals(bodyType)) {
                return convertScalar(value, bodyType, "body");
            }
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> values)) {
                throw new IllegalArgumentException("Numeric input driver body must be a list for bodyType long-list.");
            }
            List<Object> converted = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                converted.add(convertScalar(values.get(index), "long", "body[" + index + "]"));
            }
            return converted;
        }

        private static Object convertScalar(Object value, String type, String location) {
            if (value == null) {
                return null;
            }
            try {
                return switch (type) {
                    case "long" -> Long.valueOf(value.toString());
                    case "integer" -> Integer.valueOf(value.toString());
                    default -> throw new IllegalArgumentException("Unsupported numeric input type '" + type + "'.");
                };
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Numeric input driver " + location + " cannot be represented as " + type + ".", exception
                );
            }
        }
    }
}
