package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.SftpTriggerSnapshotRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SftpTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "sftp-trigger";
    }

    @Override
    public Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return Map.of("nodeId", requireNodeId(definition));
    }

    @Override
    public SnapshotScenarioDriver create(SnapshotExecutionScenario scenario, SnapshotScenarioDriverDefinition definition,
                                         List<SnapshotScenarioInvocation> invocations) {
        String nodeId = requireNodeId(definition);
        Map<String, Map<String, Object>> options = new LinkedHashMap<>();
        for (SnapshotScenarioInvocation invocation : invocations) {
            if (!invocation.getHeaders().isEmpty() || !invocation.getProperties().isEmpty()) {
                throw new IllegalArgumentException("SFTP trigger input cannot define Camel headers or exchange properties.");
            }
            SnapshotScenarioDriverDefinition selected = invocation.getDriver() == null ? scenario.getDriver() : invocation.getDriver();
            SftpTriggerSnapshotRuntime.validateParameters(selected.getParameters(), false);
            options.put(invocation.getId(), selected.getParameters());
        }
        return new SnapshotScenarioDriver() {
            private SftpTriggerSnapshotRuntime runtime;

            @Override
            public void configure(CamelContext context) {
                runtime = context.getRegistry().lookupByNameAndType(
                        SftpTriggerSnapshotRuntime.registryName(nodeId), SftpTriggerSnapshotRuntime.class);
                if (runtime == null) {
                    throw new IllegalStateException("SFTP trigger scenario '" + scenario.getId()
                            + "' requires an sftp-trigger fixture for node '" + nodeId + "'.");
                }
            }

            @Override
            public Exchange execute(ProducerTemplate template, SnapshotScenarioInvocation invocation) throws Exception {
                return runtime.send(invocation, options.get(invocation.getId()));
            }
        };
    }

    private static String requireNodeId(SnapshotScenarioDriverDefinition definition) {
        Object nodeId = definition.getParameters().get("nodeId");
        if (!(nodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("SFTP trigger driver requires a sourceElementId or nodeId.");
        }
        return value;
    }
}
