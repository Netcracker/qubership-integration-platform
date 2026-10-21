package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.start;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextStarted;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_SPECIFICATIONS;

@Slf4j
@Component
@OnAfterDeploymentContextStarted
public class McpToolPublisher implements DeploymentProcessingAction {
    private final McpSyncServer mcpSyncServer;

    @Autowired
    public McpToolPublisher(McpSyncServer mcpSyncServer) {
        this.mcpSyncServer = mcpSyncServer;
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
        specifications.forEach(specification -> {
            log.debug("Registering MCP tool: {}", specification.tool());
            mcpSyncServer.addTool(specification);
        });
    }
}
