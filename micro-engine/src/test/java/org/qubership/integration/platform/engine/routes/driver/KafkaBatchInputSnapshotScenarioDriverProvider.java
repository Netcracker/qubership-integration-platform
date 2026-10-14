package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultMessage;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class KafkaBatchInputSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final Set<String> PARAMETERS = Set.of("endpointUri", "itemType", "containerType");
    private static final Set<String> ITEM_TYPES = Set.of("value", "message", "exchange");
    private static final Set<String> CONTAINER_TYPES = Set.of("list", "iterator");

    @Override
    public String getId() {
        return "kafkaBatchInput";
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
                throw new IllegalArgumentException("Kafka batch input driver has unknown parameter '" + parameter + "'.");
            }
        }
        String endpointUri = requiredString(parameters, "endpointUri");
        String itemType = requiredString(parameters, "itemType");
        String containerType = requiredString(parameters, "containerType");
        if (!ITEM_TYPES.contains(itemType)) {
            throw new IllegalArgumentException("Kafka batch input driver itemType must be value, message, or exchange.");
        }
        if (!CONTAINER_TYPES.contains(containerType)) {
            throw new IllegalArgumentException("Kafka batch input driver containerType must be list or iterator.");
        }
        return new KafkaBatchInputSnapshotScenarioDriver(endpointUri, itemType, containerType);
    }

    private static String requiredString(Map<String, Object> parameters, String parameter) {
        Object value = parameters.get(parameter);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Kafka batch input driver parameter '" + parameter + "' must be a nonblank string.");
        }
        return string;
    }

    private record KafkaBatchInputSnapshotScenarioDriver(
            String endpointUri,
            String itemType,
            String containerType
    ) implements SnapshotScenarioDriver {
        @Override
        public Exchange execute(ProducerTemplate producerTemplate, SnapshotScenarioInvocation invocation) {
            if (!(invocation.getBody() instanceof List<?> values)) {
                throw new IllegalArgumentException("Kafka batch input driver body must be a list.");
            }
            List<BatchItem> items = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                items.add(parseItem(values.get(index), index));
            }
            return producerTemplate.request(endpointUri, request -> {
                List<Object> batch = new ArrayList<>(items.size());
                items.forEach(item -> batch.add(createItem(request.getContext(), item)));
                request.getMessage().setBody("iterator".equals(containerType) ? batch.iterator() : batch);
                invocation.getHeaders().forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
                request.setProperty("snapshot.kafkaBatchItems", Collections.unmodifiableList(batch));
            });
        }

        private BatchItem parseItem(Object value, int index) {
            if ("value".equals(itemType)) {
                return new BatchItem(value, Map.of());
            }
            if (!(value instanceof Map<?, ?> fields) || !fields.containsKey("body")
                    || !Set.of("body", "headers").containsAll(fields.keySet())) {
                throw new IllegalArgumentException(
                        "Kafka batch input driver body[" + index + "] requires a body field and optional headers."
                );
            }
            Map<String, Object> headers = new LinkedHashMap<>();
            if (fields.containsKey("headers")) {
                if (!(fields.get("headers") instanceof Map<?, ?> source)) {
                    throw new IllegalArgumentException("Kafka batch input driver body[" + index + "].headers must be a map.");
                }
                source.forEach((key, headerValue) -> {
                    if (!(key instanceof String header) || header.isBlank()) {
                        throw new IllegalArgumentException(
                                "Kafka batch input driver body[" + index + "].headers keys must be nonblank strings."
                        );
                    }
                    headers.put(header, headerValue);
                });
            }
            return new BatchItem(fields.get("body"), headers);
        }

        private Object createItem(CamelContext context, BatchItem item) {
            if ("value".equals(itemType)) {
                return item.body();
            }
            Exchange exchange = "exchange".equals(itemType) ? new DefaultExchange(context) : null;
            Message message = exchange == null ? new DefaultMessage(context) : exchange.getIn();
            message.setBody(item.body());
            item.headers().forEach(message::setHeader);
            return exchange == null ? message : exchange;
        }
    }

    private record BatchItem(Object body, Map<String, Object> headers) {
    }
}
