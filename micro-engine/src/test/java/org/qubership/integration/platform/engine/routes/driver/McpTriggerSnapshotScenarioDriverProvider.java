package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.McpTriggerSnapshotRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class McpTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "mcp-trigger";
    }

    @Override
    public Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return Map.of("nodeId", requireNodeId(definition));
    }

    @Override
    public SnapshotScenarioDriver create(SnapshotExecutionScenario scenario, SnapshotScenarioDriverDefinition definition,
                                         List<SnapshotScenarioInvocation> invocations) {
        String nodeId = requireNodeId(definition);
        Map<String, Map<String, Object>> parametersByInvocation = new LinkedHashMap<>();
        for (SnapshotScenarioInvocation invocation : invocations) {
            if (!invocation.getHeaders().isEmpty() || !invocation.getProperties().isEmpty()) {
                throw new IllegalArgumentException("MCP tool calls cannot define Camel headers or exchange properties.");
            }
            SnapshotScenarioDriverDefinition selected = invocation.getDriver() == null
                    ? scenario.getDriver() : invocation.getDriver();
            McpTriggerSnapshotRuntime.validateParameters(selected.getParameters());
            parametersByInvocation.put(invocation.getId(), selected.getParameters());
        }
        return new SnapshotScenarioDriver() {
            private McpTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        McpTriggerSnapshotRuntime.registryName(nodeId), McpTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("MCP trigger scenario '" + scenario.getId()
                            + "' requires an mcp-trigger fixture for node '" + nodeId + "'.");
                }
            }

            @Override
            public Exchange execute(ProducerTemplate template, SnapshotScenarioInvocation invocation) throws Exception {
                return runtime.send(invocation, parametersByInvocation.get(invocation.getId()));
            }
        };
    }

    private static String requireNodeId(SnapshotScenarioDriverDefinition definition) {
        Object nodeId = definition.getParameters().get("nodeId");
        if (!(nodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("MCP trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }
}
