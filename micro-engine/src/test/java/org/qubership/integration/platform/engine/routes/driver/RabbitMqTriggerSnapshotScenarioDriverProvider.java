package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.RabbitMqTriggerSnapshotRuntime;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class RabbitMqTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> AMQP_PROPERTIES = Set.of("contentType", "contentEncoding", "deliveryMode", "priority",
            "correlationId", "replyTo", "expiration", "messageId", "timestamp", "type", "userId", "appId", "clusterId");
    private static final Set<String> PARAMETERS = Set.of("nodeId", "exchange", "routingKey", "amqpProperties", "bodyBase64",
            "bodyRepeat", "messages", "expectedCompletions", "headersBase64");
    private static final Set<String> MESSAGE_PARAMETERS = Set.of("body", "headers", "exchange", "routingKey", "amqpProperties",
            "bodyBase64", "bodyRepeat", "headersBase64");

    @Override
    public String getId() {
        return "rabbitmq-trigger";
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
                throw new IllegalArgumentException("RabbitMQ trigger invocation '" + invocation.getId()
                        + "' cannot define Camel exchange properties.");
            }
            SnapshotScenarioDriverDefinition selected = invocation.getDriver() == null ? scenario.getDriver() : invocation.getDriver();
            Map<String, Object> options = selected.getParameters();
            validateParameters(options, false);
            parameters.put(invocation.getId(), options);
        }
        return new SnapshotScenarioDriver() {
            private RabbitMqTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        RabbitMqTriggerSnapshotRuntime.registryName(nodeId), RabbitMqTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("RabbitMQ trigger scenario '" + scenario.getId()
                            + "' requires a rabbitmq-trigger fixture for node '" + nodeId + "'.");
                }
            }

            @Override
            public Exchange execute(ProducerTemplate template, SnapshotScenarioInvocation invocation) throws Exception {
                return runtime.send(invocation, parameters.get(invocation.getId()));
            }
        };
    }

    private static String requireNodeId(SnapshotScenarioDriverDefinition definition) {
        Object nodeId = definition.getParameters().get("nodeId");
        if (!(nodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("RabbitMQ trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }

    private static void validateParameters(Map<?, ?> parameters, boolean message) {
        if (!(message ? MESSAGE_PARAMETERS : PARAMETERS).containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unsupported RabbitMQ trigger publication parameters: " + parameters.keySet());
        }
        if (parameters.containsKey("bodyBase64")) {
            if (!(parameters.get("bodyBase64") instanceof String text)) {
                throw new IllegalArgumentException("RabbitMQ trigger bodyBase64 must be a base64 string.");
            }
            Base64.getDecoder().decode(text);
            if (parameters.containsKey("bodyRepeat")) {
                throw new IllegalArgumentException("RabbitMQ trigger bodyBase64 and bodyRepeat cannot be combined.");
            }
        }
        for (String name : List.of("bodyRepeat", "expectedCompletions")) {
            if (parameters.containsKey(name) && (!(parameters.get(name) instanceof Integer count)
                    || count < ("bodyRepeat".equals(name) ? 1 : 0))) {
                throw new IllegalArgumentException("RabbitMQ trigger " + name + " must be "
                        + ("bodyRepeat".equals(name) ? "a positive" : "a nonnegative") + " integer.");
            }
        }
        for (String name : List.of("exchange", "routingKey")) {
            if (parameters.containsKey(name) && !(parameters.get(name) instanceof String)) {
                throw new IllegalArgumentException("RabbitMQ trigger " + name + " must be a string.");
            }
        }
        if (parameters.containsKey("headers") && !(parameters.get("headers") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("RabbitMQ trigger message headers must be a map.");
        }
        if (parameters.containsKey("headersBase64")) {
            if (!(parameters.get("headersBase64") instanceof Map<?, ?> headers)) {
                throw new IllegalArgumentException("RabbitMQ trigger headersBase64 must be a map.");
            }
            for (var header : headers.entrySet()) {
                if (!(header.getKey() instanceof String) || !(header.getValue() instanceof String text)) {
                    throw new IllegalArgumentException("RabbitMQ trigger headersBase64 requires string names and base64 values.");
                }
                Base64.getDecoder().decode(text);
            }
        }
        if (parameters.containsKey("amqpProperties")) {
            if (!(parameters.get("amqpProperties") instanceof Map<?, ?> properties) || !AMQP_PROPERTIES.containsAll(properties.keySet())) {
                throw new IllegalArgumentException("RabbitMQ trigger amqpProperties contains an unsupported property.");
            }
            for (var property : properties.entrySet()) {
                String name = (String) property.getKey();
                Object value = property.getValue();
                if (Set.of("deliveryMode", "priority", "timestamp").contains(name)) {
                    if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 0) {
                        throw new IllegalArgumentException("RabbitMQ trigger AMQP " + name + " must be a nonnegative integer.");
                    }
                    if ("deliveryMode".equals(name) && !Set.of(1L, 2L).contains(((Number) value).longValue())) {
                        throw new IllegalArgumentException("RabbitMQ trigger deliveryMode must be 1 or 2.");
                    }
                    if ("priority".equals(name) && ((Number) value).longValue() > 255) {
                        throw new IllegalArgumentException("RabbitMQ trigger priority must be between 0 and 255.");
                    }
                } else if (!(value instanceof String)) {
                    throw new IllegalArgumentException("RabbitMQ trigger AMQP " + name + " must be a string.");
                }
            }
        }
        if (parameters.containsKey("messages")) {
            if (!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                throw new IllegalArgumentException("RabbitMQ trigger messages must be a nonempty list.");
            }
            for (Object item : messages) {
                if (!(item instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("RabbitMQ trigger messages must contain maps.");
                }
                validateParameters(values, true);
            }
        }
    }
}
