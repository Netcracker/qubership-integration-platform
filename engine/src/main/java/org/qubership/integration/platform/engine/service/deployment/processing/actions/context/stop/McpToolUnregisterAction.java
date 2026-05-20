package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnStopDeploymentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.DEPLOYMENT_ID;

@Slf4j
@Component
@OnStopDeploymentContext
public class McpToolUnregisterAction implements DeploymentProcessingAction {
    private final McpSyncServer mcpSyncServer;

    @Autowired
    public McpToolUnregisterAction(
            McpSyncServer mcpSyncServer
    ) {
        this.mcpSyncServer = mcpSyncServer;
    }

    @Override
    public void execute(
            SpringCamelContext context,
            DeploymentInfo deploymentInfo,
            DeploymentConfiguration deploymentConfiguration
    ) {
        List<McpSchema.Tool> toolsToRemove = mcpSyncServer.listTools()
                .stream()
                .filter(tool -> deploymentInfo.getDeploymentId()
                        .equals(tool.meta().get(DEPLOYMENT_ID)))
                .toList();
        toolsToRemove.forEach(tool -> mcpSyncServer.removeTool(tool.name()));
    }
}
