package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.direct.DirectConsumerNotAvailableException;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Component
@OnAfterDeploymentContextCreated
public class McpToolRegistrar extends ElementProcessingAction {
    public static final String DEPLOYMENT_ID = "deploymentId";
    public static final String TOOL_SPECIFICATIONS = "mcpToolSpecifications";
    public static final String TOOL_TARGETS = "mcpToolTargets";

    private final McpJsonMapper mcpJsonMapper;
    private final McpToolTargets toolTargets;

    @Autowired
    public McpToolRegistrar(@Qualifier("jsonMapper") ObjectMapper objectMapper, McpToolTargets toolTargets) {
        this.mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);
        this.toolTargets = toolTargets;
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
        McpToolTargets.Target own = new McpToolTargets.Target(context, "direct:" + properties.getElementId());
        McpServerFeatures.SyncToolSpecification toolSpecification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((mcpExchange, request) -> {
                    Exchange result = send(own, request.arguments());
                    // A redeploy can replace the tool after the server handed it this call, and stop this
                    // context before the call reaches the trigger. The exchange then entered no route, so the
                    // deployment that serves the tool now takes it.
                    if (result.getException() instanceof DirectConsumerNotAvailableException) {
                        McpToolTargets.Target serving = toolTargets.current(tool.name()).orElse(own);
                        if (serving.context() != context) {
                            result = send(serving, request.arguments());
                        }
                    }
                    McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder();
                    Exception exception = result.getException();
                    if (nonNull(exception)) {
                        builder.isError(true).addTextContent(
                                Objects.toString(exception.getMessage(), exception.getClass().getName()));
                    } else if (isNull(tool.outputSchema())) {
                        builder.textContent(Collections.singletonList(result.getMessage().getBody(String.class)));
                    } else {
                        builder.structuredContent(result.getMessage().getBody());
                    }
                    return builder.build();
                })
                .build();
        log.debug("Prepared MCP tool: {}", toolSpecification.tool());
        toolSpecifications(context).add(toolSpecification);
        targets(context).put(tool.name(), own);
    }

    private static Exchange send(McpToolTargets.Target target, Object arguments) {
        try (ProducerTemplate producerTemplate = target.context().createProducerTemplate()) {
            return producerTemplate.request(target.endpointUri(), exchange -> exchange.getIn().setBody(arguments));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // McpToolPublisher hands these to McpToolTargets with the tools.
    @SuppressWarnings("unchecked")
    private static Map<String, McpToolTargets.Target> targets(CamelContext context) {
        Map<String, McpToolTargets.Target> targets = context.getRegistry().lookupByNameAndType(TOOL_TARGETS, Map.class);
        if (isNull(targets)) {
            targets = new HashMap<>();
            context.getRegistry().bind(TOOL_TARGETS, targets);
        }
        return targets;
    }

    // McpToolPublisher offers these once the context has started.
    @SuppressWarnings("unchecked")
    private static List<McpServerFeatures.SyncToolSpecification> toolSpecifications(CamelContext context) {
        List<McpServerFeatures.SyncToolSpecification> specifications =
                context.getRegistry().lookupByNameAndType(TOOL_SPECIFICATIONS, List.class);
        if (isNull(specifications)) {
            specifications = new ArrayList<>();
            context.getRegistry().bind(TOOL_SPECIFICATIONS, specifications);
        }
        return specifications;
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
                        false))
                .meta(Map.of(DEPLOYMENT_ID, deploymentInfo.getDeploymentId()))
                .inputSchema(mcpJsonMapper, props.get("inputSchema"));
        String outputSchema = props.get("outputSchema");
        if (StringUtils.isNotBlank(outputSchema)) {
            toolBuilder.outputSchema(mcpJsonMapper, outputSchema);
        }
        return toolBuilder.build();
    }
}
