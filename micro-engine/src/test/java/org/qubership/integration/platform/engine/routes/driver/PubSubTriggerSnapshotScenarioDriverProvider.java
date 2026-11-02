package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.PubSubTriggerSnapshotRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class PubSubTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> MESSAGE_PARAMETERS = Set.of(
            "body", "dataEncoding", "attributes", "orderingKey", "messageCount");
    private static final Set<String> PARAMETERS = Set.of(
            "nodeId", "dataEncoding", "attributes", "orderingKey", "messageCount", "messages", "observeOnly");

    @Override
    public String getId() {
        return "pubsub-trigger";
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
                throw new IllegalArgumentException("Pub/Sub trigger invocation '" + invocation.getId()
                        + "' cannot define Camel exchange properties.");
            }
            SnapshotScenarioDriverDefinition invocationDriver = invocation.getDriver() == null
                    ? scenario.getDriver() : invocation.getDriver();
            Map<String, Object> options = invocationDriver.getParameters();
            validateParameters(options);
            if (Boolean.TRUE.equals(options.get("observeOnly"))) {
                if (invocation.getBody() != null || !invocation.getHeaders().isEmpty()
                        || !Set.of("nodeId", "observeOnly").containsAll(options.keySet())) {
                    throw new IllegalArgumentException("Pub/Sub observation cannot define a body, headers, or publication options.");
                }
            } else {
                PubSubTriggerSnapshotRuntime.messages(invocation.getBody(), invocation.getHeaders(), options);
            }
            parameters.put(invocation.getId(), options);
        }
        return new SnapshotScenarioDriver() {
            private PubSubTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        PubSubTriggerSnapshotRuntime.registryName(nodeId), PubSubTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("Pub/Sub trigger scenario '" + scenario.getId()
                            + "' requires a pubsub-trigger fixture for node '" + nodeId + "'.");
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
            throw new IllegalArgumentException("Pub/Sub trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }

    private static void validateParameters(Map<?, ?> parameters) {
        if (!PARAMETERS.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unsupported Pub/Sub trigger parameters: " + parameters.keySet());
        }
        validateMessageOptions(parameters);
        if (parameters.containsKey("observeOnly") && !(parameters.get("observeOnly") instanceof Boolean)) {
            throw new IllegalArgumentException("Pub/Sub observeOnly must be a boolean.");
        }
        if (parameters.containsKey("messages")) {
            if (!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                throw new IllegalArgumentException("Pub/Sub messages must be a nonempty list.");
            }
            for (Object item : messages) {
                if (!(item instanceof Map<?, ?> message) || !MESSAGE_PARAMETERS.containsAll(message.keySet())) {
                    throw new IllegalArgumentException("Pub/Sub message entries support body, dataEncoding, attributes, orderingKey, and messageCount.");
                }
                validateMessageOptions(message);
            }
        }
    }

    private static void validateMessageOptions(Map<?, ?> parameters) {
        if (parameters.containsKey("dataEncoding") && !"utf8".equals(parameters.get("dataEncoding"))
                && !"bytes".equals(parameters.get("dataEncoding"))) {
            throw new IllegalArgumentException("Pub/Sub dataEncoding supports utf8 and bytes.");
        }
        if (parameters.containsKey("orderingKey") && !(parameters.get("orderingKey") instanceof String)) {
            throw new IllegalArgumentException("Pub/Sub orderingKey must be a string.");
        }
        if (parameters.containsKey("messageCount") && (!(parameters.get("messageCount") instanceof Number value)
                || value.intValue() < 1 || value.doubleValue() != value.intValue())) {
            throw new IllegalArgumentException("Pub/Sub messageCount must be a positive integer.");
        }
        if (parameters.containsKey("attributes")) {
            if (!(parameters.get("attributes") instanceof Map<?, ?> attributes)) {
                throw new IllegalArgumentException("Pub/Sub attributes must be a map of strings.");
            }
            attributes.forEach((key, value) -> {
                if (!(key instanceof String) || !(value instanceof String)) {
                    throw new IllegalArgumentException("Pub/Sub attribute names and values must be strings.");
                }
            });
        }
    }
}
