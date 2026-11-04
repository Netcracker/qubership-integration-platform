package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.start;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.DefaultRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolTargets;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_SPECIFICATIONS;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.McpToolRegistrar.TOOL_TARGETS;

class McpToolPublisherTest {

    private McpSyncServer mcpSyncServer;
    private SpringCamelContext context;
    private McpToolTargets toolTargets;
    private McpToolPublisher publisher;

    @BeforeEach
    void setUp() {
        mcpSyncServer = mock(McpSyncServer.class);
        context = mock(SpringCamelContext.class);
        when(context.getRegistry()).thenReturn(new DefaultRegistry());
        toolTargets = new McpToolTargets();
        publisher = new McpToolPublisher(mcpSyncServer, toolTargets);
    }

    @Test
    void addsEveryToolTheContextPrepared() {
        McpServerFeatures.SyncToolSpecification first = specification("first");
        McpServerFeatures.SyncToolSpecification second = specification("second");
        context.getRegistry().bind(TOOL_SPECIFICATIONS, List.of(first, second));
        context.getRegistry().bind(TOOL_TARGETS, Map.of("first", target("direct:a"), "second", target("direct:b")));

        publisher.execute(context, deploymentInfo(), null);

        verify(mcpSyncServer).addTool(first);
        verify(mcpSyncServer).addTool(second);
    }

    @Test
    void publishesEachToolTargetBeforeItsTool() {
        McpServerFeatures.SyncToolSpecification first = specification("first");
        context.getRegistry().bind(TOOL_SPECIFICATIONS, List.of(first));
        context.getRegistry().bind(TOOL_TARGETS, Map.of("first", target("direct:a")));
        // The target is there by the time the server offers the tool.
        doAnswer(invocation -> {
            assertEquals(Optional.of(target("direct:a")), toolTargets.current("first"));
            return null;
        }).when(mcpSyncServer).addTool(first);

        publisher.execute(context, deploymentInfo(), null);

        verify(mcpSyncServer).addTool(first);
    }

    @Test
    void addsNothingWhenTheContextPreparedNoTool() {
        publisher.execute(context, deploymentInfo(), null);

        verifyNoInteractions(mcpSyncServer);
    }

    private McpServerFeatures.SyncToolSpecification specification(String name) {
        McpServerFeatures.SyncToolSpecification specification = mock(McpServerFeatures.SyncToolSpecification.class);
        when(specification.tool()).thenReturn(McpSchema.Tool.builder().name(name).build());
        return specification;
    }

    private McpToolTargets.Target target(String endpointUri) {
        return new McpToolTargets.Target(context, endpointUri);
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder().deploymentId("deployment-123").chainId("chain-1").build();
    }
}
