package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.camel.spring.SpringCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolTargets;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.DEPLOYMENT_ID;

class McpToolUnregisterActionTest {

    private McpSyncServer mcpSyncServer;
    private McpToolTargets toolTargets;
    private McpToolUnregisterAction action;

    @BeforeEach
    void setUp() {
        mcpSyncServer = mock(McpSyncServer.class);
        toolTargets = new McpToolTargets();
        action = new McpToolUnregisterAction(mcpSyncServer, toolTargets);
    }

    @Test
    void executeRemovesToolsMatchingDeploymentId() {
        String deploymentId = "deploy-1";
        McpSchema.Tool matchingTool = tool("tool-a", deploymentId);
        when(mcpSyncServer.listTools()).thenReturn(List.of(matchingTool));

        action.execute(null, deploymentInfo(deploymentId), null);

        verify(mcpSyncServer).removeTool("tool-a");
    }

    @Test
    void executeWithdrawsTheTargetsOfTheStoppedContextOnly() {
        SpringCamelContext stopped = mock(SpringCamelContext.class);
        SpringCamelContext serving = mock(SpringCamelContext.class);
        toolTargets.publish("tool-a", new McpToolTargets.Target(stopped, "direct:a"));
        // A redeploy published tool-b's new deployment before it stopped the old one.
        toolTargets.publish("tool-b", new McpToolTargets.Target(serving, "direct:b"));
        when(mcpSyncServer.listTools()).thenReturn(List.of());

        action.execute(null, deploymentInfo("deploy-1"), null);
        assertTrue(toolTargets.current("tool-a").isPresent(), "a stop with no context withdraws nothing");

        action.execute(stopped, deploymentInfo("deploy-1"), null);

        assertTrue(toolTargets.current("tool-a").isEmpty());
        assertTrue(toolTargets.current("tool-b").isPresent());
    }

    @Test
    void executeDoesNotRemoveToolsFromOtherDeployments() {
        McpSchema.Tool otherTool = tool("tool-b", "deploy-other");
        when(mcpSyncServer.listTools()).thenReturn(List.of(otherTool));

        action.execute(null, deploymentInfo("deploy-1"), null);

        verify(mcpSyncServer, never()).removeTool(any());
    }

    @Test
    void executeDoesNothingWhenNoToolsPresent() {
        when(mcpSyncServer.listTools()).thenReturn(List.of());

        action.execute(null, deploymentInfo("deploy-1"), null);

        verify(mcpSyncServer, never()).removeTool(any());
    }

    @Test
    void executeRemovesOnlyMatchingToolsWhenMultiplePresent() {
        String deploymentId = "deploy-1";
        McpSchema.Tool matchingTool1 = tool("tool-x", deploymentId);
        McpSchema.Tool matchingTool2 = tool("tool-y", deploymentId);
        McpSchema.Tool otherTool = tool("tool-z", "deploy-other");
        when(mcpSyncServer.listTools()).thenReturn(List.of(matchingTool1, matchingTool2, otherTool));

        action.execute(null, deploymentInfo(deploymentId), null);

        verify(mcpSyncServer).removeTool("tool-x");
        verify(mcpSyncServer).removeTool("tool-y");
        verify(mcpSyncServer, never()).removeTool("tool-z");
    }

    @Test
    void executeDoesNothingWhenDeploymentConfigurationIsNull() {
        when(mcpSyncServer.listTools()).thenReturn(List.of());

        action.execute(null, deploymentInfo("deploy-1"), (DeploymentConfiguration) null);

        verify(mcpSyncServer, never()).removeTool(any());
    }

    private McpSchema.Tool tool(String name, String deploymentId) {
        return McpSchema.Tool.builder()
                .name(name)
                .meta(Map.of(DEPLOYMENT_ID, deploymentId))
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null))
                .build();
    }

    private DeploymentInfo deploymentInfo(String deploymentId) {
        return DeploymentInfo.builder()
                .deploymentId(deploymentId)
                .build();
    }
}
