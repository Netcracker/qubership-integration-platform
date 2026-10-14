package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BinaryInputSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> PARAMETERS = Set.of("endpointUri", "bodyType", "bufferPosition");
    private static final Set<String> BODY_TYPES = Set.of("bytes", "byte-buffer", "input-stream", "batch");

    @Override
    public String getId() {
        return "binaryInput";
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
                throw new IllegalArgumentException("Binary input driver has unknown parameter '" + parameter + "'.");
            }
        }
        String endpointUri = requiredString(parameters, "endpointUri");
        String bodyType = requiredString(parameters, "bodyType");
        if (!BODY_TYPES.contains(bodyType)) {
            throw new IllegalArgumentException("Binary input driver bodyType must be bytes, byte-buffer, input-stream, or batch.");
        }
        int bufferPosition = 0;
        if (parameters.containsKey("bufferPosition")) {
            if (!"byte-buffer".equals(bodyType)) {
                throw new IllegalArgumentException("Binary input driver bufferPosition requires bodyType byte-buffer.");
            }
            if (!(parameters.get("bufferPosition") instanceof Integer position) || position < 0) {
                throw new IllegalArgumentException("Binary input driver bufferPosition must be a nonnegative integer.");
            }
            bufferPosition = position;
        }
        return new BinaryInputSnapshotScenarioDriver(endpointUri, bodyType, bufferPosition);
    }

    private static String requiredString(Map<String, Object> parameters, String parameter) {
        Object value = parameters.get(parameter);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Binary input driver parameter '" + parameter + "' must be a nonblank string.");
        }
        return string;
    }

    private record BinaryInputSnapshotScenarioDriver(
            String endpointUri,
            String bodyType,
            int bufferPosition
    ) implements SnapshotScenarioDriver {
        @Override
        public Exchange execute(ProducerTemplate producerTemplate, SnapshotScenarioInvocation invocation) {
            Object body = convertBody(invocation.getBody());
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(body);
                invocation.getHeaders().forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }

        private Object convertBody(Object value) {
            if (!"batch".equals(bodyType)) {
                if (value == null) {
                    return null;
                }
                byte[] bytes = decodeHex(value, "body");
                if ("input-stream".equals(bodyType)) {
                    return new ByteArrayInputStream(bytes);
                }
                if ("byte-buffer".equals(bodyType)) {
                    if (bufferPosition > bytes.length) {
                        throw new IllegalArgumentException("Binary input driver bufferPosition exceeds the body length.");
                    }
                    return ByteBuffer.wrap(bytes).position(bufferPosition);
                }
                return bytes;
            }
            if (!(value instanceof List<?> values)) {
                throw new IllegalArgumentException("Binary input driver body must be a list for bodyType batch.");
            }
            List<Object> converted = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                Object entry = values.get(index);
                if (entry instanceof Map<?, ?> marker && marker.containsKey("hex")) {
                    if (marker.size() != 1) {
                        throw new IllegalArgumentException(
                                "Binary input driver body[" + index + "] hex marker must contain only the hex field."
                        );
                    }
                    entry = decodeHex(marker.get("hex"), "body[" + index + "].hex");
                }
                converted.add(entry);
            }
            return converted;
        }

        private static byte[] decodeHex(Object value, String location) {
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException("Binary input driver " + location + " must be a hex string.");
            }
            try {
                return HexFormat.of().parseHex(string);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Binary input driver " + location + " contains invalid hex.", exception);
            }
        }
    }
}
