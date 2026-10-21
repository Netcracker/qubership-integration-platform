package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.start;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.DefaultRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_SPECIFICATIONS;

class McpToolPublisherTest {

    private McpSyncServer mcpSyncServer;
    private SpringCamelContext context;
    private McpToolPublisher publisher;

    @BeforeEach
    void setUp() {
        mcpSyncServer = mock(McpSyncServer.class);
        context = mock(SpringCamelContext.class);
        when(context.getRegistry()).thenReturn(new DefaultRegistry());
        publisher = new McpToolPublisher(mcpSyncServer);
    }

    @Test
    void addsEveryToolTheContextPrepared() {
        McpServerFeatures.SyncToolSpecification first = mock(McpServerFeatures.SyncToolSpecification.class);
        McpServerFeatures.SyncToolSpecification second = mock(McpServerFeatures.SyncToolSpecification.class);
        context.getRegistry().bind(TOOL_SPECIFICATIONS, List.of(first, second));

        publisher.execute(context, deploymentInfo(), null);

        verify(mcpSyncServer).addTool(first);
        verify(mcpSyncServer).addTool(second);
    }

    @Test
    void addsNothingWhenTheContextPreparedNoTool() {
        publisher.execute(context, deploymentInfo(), null);

        verifyNoInteractions(mcpSyncServer);
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder().deploymentId("deployment-123").chainId("chain-1").build();
    }
}
