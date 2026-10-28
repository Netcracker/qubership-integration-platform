package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.KafkaTriggerSnapshotRecordCodec;
import org.qubership.integration.platform.engine.routes.fixture.KafkaTriggerSnapshotRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class KafkaTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "kafka-trigger";
    }

    @Override
    public Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return Map.of("nodeId", requireNodeId(definition));
    }

    @Override
    public SnapshotScenarioDriver create(SnapshotExecutionScenario scenario, SnapshotScenarioDriverDefinition definition,
                                         List<SnapshotScenarioInvocation> invocations) {
        String nodeId = requireNodeId(definition);
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        for (SnapshotScenarioInvocation invocation : invocations) {
            if (!invocation.getProperties().isEmpty()) {
                throw new IllegalArgumentException("Kafka trigger invocation '" + invocation.getId()
                        + "' cannot define Camel exchange properties.");
            }
            validateHeaders(invocation.getHeaders());
            SnapshotScenarioDriverDefinition invocationDriver = invocation.getDriver() == null
                    ? scenario.getDriver() : invocation.getDriver();
            Map<String, Object> options = invocationDriver.getParameters();
            validateParameters(options, false, Map.of());
            KafkaTriggerSnapshotRecordCodec.encode(invocation.getBody(), options.get("valueEncoding"));
            if ((Boolean.TRUE.equals(options.get("consumeStartup")) || Boolean.TRUE.equals(options.get("awaitConsumerFailure")))
                    && (invocation.getBody() != null || !invocation.getHeaders().isEmpty())) {
                throw new IllegalArgumentException("Kafka trigger startup observation cannot define an invocation body or headers.");
            }
            parameters.put(invocation.getId(), options);
        }
        return new SnapshotScenarioDriver() {
            private KafkaTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        KafkaTriggerSnapshotRuntime.registryName(nodeId), KafkaTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("Kafka trigger scenario '" + scenario.getId()
                            + "' requires a kafka-trigger fixture for node '" + nodeId + "'.");
                }
            }

            @Override
            public Exchange execute(ProducerTemplate template, SnapshotScenarioInvocation invocation) throws Exception {
                Map<String, Object> options = parameters.get(invocation.getId());
                return Boolean.TRUE.equals(options.get("awaitConsumerFailure"))
                        ? runtime.awaitConsumerFailure(template.getCamelContext()) : runtime.send(invocation, options);
            }
        };
    }

    private static String requireNodeId(SnapshotScenarioDriverDefinition definition) {
        Object nodeId = definition.getParameters().get("nodeId");
        if (!(nodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Kafka trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }

    private static void validateParameters(Map<?, ?> parameters, boolean message, Map<?, ?> defaults) {
        Set<String> allowed = message
                ? Set.of("body", "headers", "topic", "partition", "timestamp", "key", "keyEncoding", "valueEncoding",
                        "recordHeaders", "messageCount")
                : Set.of("nodeId", "topic", "partition", "timestamp", "key", "recordHeaders", "messageCount", "messages",
                        "keyEncoding", "valueEncoding", "consumeStartup", "awaitConsumerFailure");
        if (!allowed.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unsupported Kafka trigger parameters: " + parameters.keySet());
        }
        KafkaTriggerSnapshotRecordCodec.encode(parameters.get("key"), parameters.containsKey("keyEncoding")
                ? parameters.get("keyEncoding") : defaults.get("keyEncoding"));
        KafkaTriggerSnapshotRecordCodec.encode(parameters.get("body"), parameters.containsKey("valueEncoding")
                ? parameters.get("valueEncoding") : defaults.get("valueEncoding"));
        for (String name : List.of("consumeStartup", "awaitConsumerFailure")) {
            if (parameters.containsKey(name) && !(parameters.get(name) instanceof Boolean)) {
                throw new IllegalArgumentException("Kafka trigger " + name + " must be a boolean.");
            }
        }
        if (Boolean.TRUE.equals(parameters.get("consumeStartup")) || Boolean.TRUE.equals(parameters.get("awaitConsumerFailure"))) {
            if (Boolean.TRUE.equals(parameters.get("consumeStartup")) && Boolean.TRUE.equals(parameters.get("awaitConsumerFailure"))
                    || parameters.keySet().stream().anyMatch(Set.of("topic", "partition", "timestamp", "key", "recordHeaders",
                            "keyEncoding", "valueEncoding", "messageCount", "messages")::contains)) {
                throw new IllegalArgumentException("Kafka trigger startup observation cannot define publication options or combine observation modes.");
            }
        }
        if (parameters.containsKey("topic") && (!(parameters.get("topic") instanceof String topic) || topic.isBlank())) {
            throw new IllegalArgumentException("Kafka trigger topic must be a nonblank string.");
        }
        for (String name : List.of("partition", "timestamp", "messageCount")) {
            if (parameters.containsKey(name) && (!(parameters.get(name) instanceof Number value)
                    || value.longValue() < ("messageCount".equals(name) ? 1 : 0) || value.doubleValue() != value.longValue())) {
                throw new IllegalArgumentException("Kafka trigger " + name + " must be "
                        + ("messageCount".equals(name) ? "a positive" : "a nonnegative") + " integer.");
            }
        }
        if (parameters.containsKey("headers")) {
            validateHeaders(parameters.get("headers"));
        }
        if (parameters.containsKey("recordHeaders")) {
            if (!(parameters.get("recordHeaders") instanceof List<?> headers)) {
                throw new IllegalArgumentException("Kafka trigger recordHeaders must be a list.");
            }
            for (Object item : headers) {
                if (!(item instanceof Map<?, ?> header) || !header.keySet().equals(Set.of("key", "value"))
                        || !(header.get("key") instanceof String)) {
                    throw new IllegalArgumentException("Kafka trigger recordHeaders entries require a string key and a value.");
                }
                validateString(header.get("value"), "recordHeaders.value");
            }
        }
        if (parameters.containsKey("messages")) {
            if (!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                throw new IllegalArgumentException("Kafka trigger messages must be a nonempty list.");
            }
            for (Object item : messages) {
                if (!(item instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("Kafka trigger messages entries must be maps.");
                }
                validateParameters(values, true, parameters);
            }
        }
    }

    private static void validateHeaders(Object headers) {
        if (!(headers instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Kafka trigger headers must be a map.");
        }
        values.forEach((name, value) -> {
            if (!(name instanceof String)) {
                throw new IllegalArgumentException("Kafka trigger header names must be strings.");
            }
            validateString(value, "header '" + name + "'");
        });
    }

    private static void validateString(Object value, String name) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Kafka trigger " + name + " must be a string or null.");
        }
    }
}
