package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.SchedulerSnapshotRuntime;

import java.util.List;
import java.util.Map;
import java.util.Set;

class SchedulerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "scheduler";
    }

    @Override
    public Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return Map.of("nodeId", requireNodeId(definition));
    }

    @Override
    public SnapshotScenarioDriver create(SnapshotExecutionScenario scenario, SnapshotScenarioDriverDefinition definition,
                                         List<SnapshotScenarioInvocation> invocations) {
        String nodeId = requireNodeId(definition);
        for (SnapshotScenarioInvocation invocation : invocations) {
            if (invocation.getBody() != null || !invocation.getHeaders().isEmpty() || !invocation.getProperties().isEmpty()) {
                throw new IllegalArgumentException("Scheduler invocations cannot inject a body, headers, or exchange properties.");
            }
        }
        return new SnapshotScenarioDriver() {
            private SchedulerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        SchedulerSnapshotRuntime.registryName(nodeId), SchedulerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("Scheduler scenario '" + scenario.getId()
                            + "' requires a scheduler fixture for node '" + nodeId + "'.");
                }
            }

            @Override
            public Exchange execute(ProducerTemplate template, SnapshotScenarioInvocation invocation) throws Exception {
                return runtime.await();
            }
        };
    }

    private static String requireNodeId(SnapshotScenarioDriverDefinition definition) {
        if (!Set.of("nodeId").containsAll(definition.getParameters().keySet())) {
            throw new IllegalArgumentException("Scheduler driver accepts only nodeId or sourceElementId.");
        }
        Object nodeId = definition.getParameters().get("nodeId");
        if (!(nodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Scheduler driver requires a sourceElementId or nodeId.");
        }
        return value;
    }
}
