package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.JmsTriggerSnapshotRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class JmsTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "jms-trigger";
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
                throw new IllegalArgumentException("JMS trigger invocation '" + invocation.getId()
                        + "' cannot define Camel exchange properties.");
            }
            Map<String, Object> options = (invocation.getDriver() == null ? scenario.getDriver() : invocation.getDriver()).getParameters();
            validateParameters(options);
            parameters.put(invocation.getId(), options);
        }
        return new SnapshotScenarioDriver() {
            private JmsTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        JmsTriggerSnapshotRuntime.registryName(nodeId), JmsTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("JMS trigger scenario '" + scenario.getId()
                            + "' requires an artemis-jms-trigger fixture for node '" + nodeId + "'.");
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
            throw new IllegalArgumentException("JMS trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }

    private static void validateParameters(Map<String, Object> parameters) {
        if (!Set.of("nodeId", "messageType", "jmsHeaders", "messages").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("JMS trigger driver supports only nodeId, messageType, jmsHeaders, and messages parameters.");
        }
        if (parameters.containsKey("messages")) {
            if (!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                throw new IllegalArgumentException("JMS trigger messages must be a nonempty list.");
            }
            for (Object item : messages) {
                if (!(item instanceof Map<?, ?> message)
                        || !Set.of("body", "headers", "messageType", "jmsHeaders").containsAll(message.keySet())) {
                    throw new IllegalArgumentException("JMS trigger batch messages support body, headers, messageType, and jmsHeaders.");
                }
                Map<String, Object> options = new LinkedHashMap<>();
                for (String key : List.of("messageType", "jmsHeaders")) {
                    if (message.containsKey(key)) {
                        options.put(key, message.get(key));
                    }
                }
                validateParameters(options);
                if (message.containsKey("headers") && !(message.get("headers") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("JMS trigger message headers must be a map.");
                }
            }
        }
        if (!Set.of("Text", "Bytes", "Map", "Object", "Stream", "Message").contains(parameters.getOrDefault("messageType", "Text"))) {
            throw new IllegalArgumentException("Unsupported JMS messageType '" + parameters.get("messageType") + "'.");
        }
        Object headers = parameters.getOrDefault("jmsHeaders", Map.of());
        if (!(headers instanceof Map<?, ?> values)
                || !Set.of("correlationId", "type", "priority", "deliveryMode", "replyTo", "timeToLiveMillis").containsAll(values.keySet())) {
            throw new IllegalArgumentException("JMS trigger jmsHeaders supports correlationId, type, priority, deliveryMode, replyTo, and timeToLiveMillis.");
        }
        for (String name : List.of("correlationId", "type", "replyTo")) {
            if (values.containsKey(name) && !(values.get(name) instanceof String)) {
                throw new IllegalArgumentException("JMS trigger jmsHeaders." + name + " must be a string.");
            }
        }
        if (values.containsKey("priority") && (!(values.get("priority") instanceof Integer priority) || priority < 0 || priority > 9)) {
            throw new IllegalArgumentException("JMS trigger priority must be an integer from 0 to 9.");
        }
        if (values.containsKey("timeToLiveMillis") && (!(values.get("timeToLiveMillis") instanceof Number ttl)
                || ttl.longValue() < 0 || ttl.doubleValue() != ttl.longValue())) {
            throw new IllegalArgumentException("JMS trigger timeToLiveMillis must be a nonnegative integer.");
        }
        if (values.containsKey("deliveryMode") && !Set.of("PERSISTENT", "NON_PERSISTENT").contains(values.get("deliveryMode"))) {
            throw new IllegalArgumentException("JMS trigger deliveryMode must be PERSISTENT or NON_PERSISTENT.");
        }
    }
}
