package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.start;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolTargets;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextStarted;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_SPECIFICATIONS;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_TARGETS;

@Slf4j
@Component
@OnAfterDeploymentContextStarted
public class McpToolPublisher implements DeploymentProcessingAction {
    private final McpSyncServer mcpSyncServer;
    private final McpToolTargets toolTargets;

    @Autowired
    public McpToolPublisher(McpSyncServer mcpSyncServer, McpToolTargets toolTargets) {
        this.mcpSyncServer = mcpSyncServer;
        this.toolTargets = toolTargets;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(
            SpringCamelContext context,
            DeploymentInfo deploymentInfo,
            DeploymentConfiguration deploymentConfiguration
    ) {
        List<McpServerFeatures.SyncToolSpecification> specifications =
                context.getRegistry().lookupByNameAndType(TOOL_SPECIFICATIONS, List.class);
        if (isNull(specifications)) {
            return;
        }
        Map<String, McpToolTargets.Target> targets = context.getRegistry().lookupByNameAndType(TOOL_TARGETS, Map.class);
        specifications.forEach(specification -> {
            log.debug("Registering MCP tool: {}", specification.tool());
            // Before the tool, so a call the replaced tool cannot take already finds this deployment.
            toolTargets.publish(specification.tool().name(), targets.get(specification.tool().name()));
            mcpSyncServer.addTool(specification);
        });
    }
}
