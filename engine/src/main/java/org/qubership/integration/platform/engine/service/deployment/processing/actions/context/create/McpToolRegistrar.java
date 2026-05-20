package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

import static java.util.Objects.isNull;

@Slf4j
@Component
@OnAfterDeploymentContextCreated
public class McpToolRegistrar extends ElementProcessingAction {
    public static final String DEPLOYMENT_ID = "deploymentId";

    private final McpSyncServer mcpSyncServer;
    private final McpJsonMapper mcpJsonMapper;

    @Autowired
    public McpToolRegistrar(
            McpSyncServer mcpSyncServer,
            @Qualifier("jsonMapper") ObjectMapper objectMapper
    ) {
        this.mcpSyncServer = mcpSyncServer;
        this.mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        String elementType = properties.getProperties().get(CamelConstants.ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return ChainElementType.MCP_TRIGGER.equals(chainElementType);
    }

    @Override
    public void apply(SpringCamelContext context, ElementProperties properties, DeploymentInfo deploymentInfo) {
        McpSchema.Tool tool = buildMcpTool(properties, deploymentInfo);
        McpServerFeatures.SyncToolSpecification toolSpecification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((mcpExchange, request) -> {
                    ProducerTemplate producerTemplate = context.createProducerTemplate();
                    String endpointUri = "direct:" + properties.getElementId();
                    Exchange result = producerTemplate.request(endpointUri, exchange ->
                        exchange.getIn().setBody(request.arguments()));
                    McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder();
                    if (isNull(tool.outputSchema())) {
                        builder.textContent(Collections.singletonList(result.getMessage().getBody(String.class)));
                    } else {
                        builder.structuredContent(result.getMessage().getBody());
                    }
                    return builder.build();
                })
                .build();
        log.debug("Registering MCP tool: {}", toolSpecification.tool());
        mcpSyncServer.addTool(toolSpecification);
    }

    private McpSchema.Tool buildMcpTool(ElementProperties properties, DeploymentInfo deploymentInfo) {
        Map<String, String> props = properties.getProperties();
        McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder();
        toolBuilder
                .name(props.get("name"))
                .description(props.get("description"))
                .title(props.get("title"))
                .annotations(new McpSchema.ToolAnnotations(
                        props.get("title"),
                        Boolean.valueOf(props.get("readOnly")),
                        Boolean.valueOf(props.get("destructive")),
                        Boolean.valueOf(props.get("idempotent")),
                        Boolean.valueOf(props.get("openWorld")),
                        Boolean.valueOf(props.get("requiresLocal"))))
                .meta(Map.of(DEPLOYMENT_ID, deploymentInfo.getDeploymentId()))
                .inputSchema(mcpJsonMapper, props.get("inputSchema"));
        String outputSchema = props.get("outputSchema");
        if (StringUtils.isNotBlank(outputSchema)) {
            toolBuilder.outputSchema(mcpJsonMapper, outputSchema);
        }
        return toolBuilder.build();
    }
}
