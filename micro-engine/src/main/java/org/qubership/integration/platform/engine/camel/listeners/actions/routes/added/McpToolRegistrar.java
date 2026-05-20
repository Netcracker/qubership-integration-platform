package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteAdded;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.McpTriggerInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;

import java.util.Map;
import java.util.stream.StreamSupport;

@Slf4j
@OnRouteAdded
@ApplicationScoped
public class McpToolRegistrar implements EventProcessingAction<CamelEvent.RouteAddedEvent> {
    public static final String DEPLOYMENT_ID = "deploymentId";

    @Inject
    ToolManager toolManager;

    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    @Override
    public void process(CamelEvent.RouteAddedEvent event) throws Exception {
        DeploymentInfo deploymentInfo = MetadataUtil.getBean(event.getRoute(), DeploymentInfo.class);
        if (!alreadyRegistered(deploymentInfo)) {
            MetadataUtil.getElementsInfo(event.getRoute())
                    .filter(McpToolRegistrar::isMcpTrigger)
                    .forEach(elementInfo -> {
                        McpTriggerInfo mcpTriggerInfo = MetadataUtil.getBeanForElement(
                                event.getRoute(), elementInfo.getSnapshotElementId(), McpTriggerInfo.class);
                        registerMcpTool(event.getRoute(), deploymentInfo, elementInfo, mcpTriggerInfo);
                    });
        }
    }

    private boolean alreadyRegistered(DeploymentInfo deploymentInfo) {
        return StreamSupport.stream(toolManager.spliterator(), false)
                .anyMatch(tool -> deploymentInfo.getId()
                        .equals(tool.metadata().get(MetaKey.of(DEPLOYMENT_ID))));
    }

    private static boolean isMcpTrigger(ElementInfo elementInfo) {
        ChainElementType elementType = ChainElementType.fromString(elementInfo.getType());
        return ChainElementType.MCP_TRIGGER.equals(elementType);
    }

    public void registerMcpTool(Route route, DeploymentInfo deploymentInfo, ElementInfo elementInfo, McpTriggerInfo mcpTriggerInfo) {
        CamelContext context = route.getCamelContext();
        try {
            ToolManager.ToolDefinition toolDefinition = toolManager.newTool(mcpTriggerInfo.getName());
            toolDefinition
                    .setDescription(mcpTriggerInfo.getDescription())
                    .setTitle(mcpTriggerInfo.getTitle())
                    .setAnnotations(new ToolManager.ToolAnnotations(
                            mcpTriggerInfo.getTitle(),
                            mcpTriggerInfo.getReadOnly(),
                            mcpTriggerInfo.getDestructive(),
                            mcpTriggerInfo.getIdempotent(),
                            mcpTriggerInfo.getOpenWorld()))
                    .setMetadata(Map.of(MetaKey.of(DEPLOYMENT_ID), deploymentInfo.getId()))
                    .setInputSchema(objectMapper.readTree(mcpTriggerInfo.getInputSchema()));
            String outputSchema = mcpTriggerInfo.getOutputSchema();
            boolean hasOutputSchema = StringUtils.isNotBlank(outputSchema);
            if (hasOutputSchema) {
                toolDefinition.setOutputSchema(objectMapper.readTree(outputSchema));
            }
            toolDefinition.setHandler((arguments) -> {
                ProducerTemplate producerTemplate = context.createProducerTemplate();
                String endpointUri = "direct:" + elementInfo.getSnapshotElementId();
                Exchange result = producerTemplate.request(endpointUri, exchange -> {
                    // Manually setting the from route ID
                    exchange.getExchangeExtension().setFromRouteId(route.getId());
                    exchange.getIn().setBody(arguments.args());
                });
                return hasOutputSchema
                        ? ToolResponse.structuredSuccess(result.getMessage().getBody())
                        : ToolResponse.success(result.getMessage().getBody(String.class));
            });
            toolDefinition.register();
        } catch (JsonProcessingException exception) {
            throw new RuntimeException(exception);
        }
    }
}
