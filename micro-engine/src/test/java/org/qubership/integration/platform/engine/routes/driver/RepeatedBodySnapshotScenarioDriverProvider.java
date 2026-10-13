package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

class RepeatedBodySnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "repeatedBody";
    }

    @Override
    public SnapshotScenarioDriver create(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations
    ) {
        Map<String, Object> parameters = definition.getParameters();
        if (!Set.of("endpointUri", "repeat").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Repeated body driver supports only endpointUri and repeat.");
        }
        if (!(parameters.get("endpointUri") instanceof String endpointUri) || endpointUri.isBlank()) {
            throw new IllegalArgumentException("Repeated body driver requires a nonblank endpointUri.");
        }
        if (!(parameters.get("repeat") instanceof Integer repeat) || repeat <= 0) {
            throw new IllegalArgumentException("Repeated body driver repeat must be a positive integer.");
        }
        return new RepeatedBodySnapshotScenarioDriver(endpointUri, repeat);
    }

    private record RepeatedBodySnapshotScenarioDriver(String endpointUri, int repeat) implements SnapshotScenarioDriver {
        @Override
        public Exchange execute(ProducerTemplate producerTemplate, SnapshotScenarioInvocation invocation) {
            if (!(invocation.getBody() instanceof String text)) {
                throw new IllegalArgumentException("Repeated body driver requires a string body.");
            }
            String body = text.repeat(repeat);
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(body);
                invocation.getHeaders().forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }
    }
}
