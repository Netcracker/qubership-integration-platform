package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.direct.DirectConsumerNotAvailableException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolRegistrarTest {

    private static final String DEPLOYMENT_ID = "deployment-123";
    private static final String ELEMENT_ID = "element-456";
    private static final String TOOL_NAME = "my-tool";
    private static final String TOOL_DESCRIPTION = "A test tool";
    private static final String TOOL_TITLE = "My Tool";
    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"param\":{\"type\":\"string\"}}}";
    private static final String OUTPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"string\"}}}";

    private SpringCamelContext context;
    private McpToolTargets toolTargets;
    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        context = mock(SpringCamelContext.class);
        when(context.getRegistry()).thenReturn(new DefaultRegistry());
        toolTargets = new McpToolTargets();
        registrar = new McpToolRegistrar(new ObjectMapper(), toolTargets);
    }

    @Test
    void applicableToReturnsTrueForMcpTrigger() {
        ElementProperties properties = elementProperties("mcp-trigger");
        assertTrue(registrar.applicableTo(properties));
    }

    @Test
    void applicableToReturnsFalseForHttpTrigger() {
        ElementProperties properties = elementProperties("http-trigger");
        assertFalse(registrar.applicableTo(properties));
    }

    @Test
    void applicableToReturnsFalseForUnknownType() {
        ElementProperties properties = elementProperties("unknown-type");
        assertFalse(registrar.applicableTo(properties));
    }

    @Test
    void applicableToReturnsFalseWhenElementTypeIsNull() {
        ElementProperties properties = ElementProperties.builder()
                .elementId(ELEMENT_ID)
                .properties(new HashMap<>())
                .build();
        assertFalse(registrar.applicableTo(properties));
    }

    @Test
    void applyPreparesTool() {
        ElementProperties properties = mcpTriggerProperties(null);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(context, properties, deploymentInfo);

        McpSchema.Tool tool = preparedTool().tool();
        assertEquals(TOOL_NAME, tool.name());
        assertEquals(TOOL_DESCRIPTION, tool.description());
        assertEquals(TOOL_TITLE, tool.title());
        assertEquals(DEPLOYMENT_ID, tool.meta().get(McpToolRegistrar.DEPLOYMENT_ID));
    }

    @Test
    void applyRegistersToolWithOutputSchemaWhenProvided() {
        ElementProperties properties = mcpTriggerProperties(OUTPUT_SCHEMA);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(context, properties, deploymentInfo);

        McpSchema.Tool tool = preparedTool().tool();
        assertNotNull(tool.outputSchema());
    }

    @Test
    void applyRegistersToolWithoutOutputSchemaWhenBlank() {
        ElementProperties properties = mcpTriggerProperties("");
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(context, properties, deploymentInfo);

        McpSchema.Tool tool = preparedTool().tool();
        assertNull(tool.outputSchema());
    }

    @Test
    void applyRegistersToolWithCorrectAnnotations() {
        ElementProperties properties = mcpTriggerProperties(null);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(context, properties, deploymentInfo);

        McpSchema.ToolAnnotations annotations = preparedTool().tool().annotations();
        assertNotNull(annotations);
        assertTrue(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
    }

    @Test
    void callHandlerAnswersChainBody() {
        Exchange result = new DefaultExchange(new DefaultCamelContext());
        result.getMessage().setBody("{\"result\":\"ok\"}");

        McpSchema.CallToolResult callResult = callTool(result);

        assertFalse(callResult.isError());
        assertEquals(List.of(new McpSchema.TextContent("{\"result\":\"ok\"}")), callResult.content());
    }

    @Test
    void callHandlerAnswersErrorWhenChainFails() {
        Exchange result = new DefaultExchange(new DefaultCamelContext());
        result.setException(new IllegalStateException("chain failed"));

        McpSchema.CallToolResult callResult = callTool(result);

        assertTrue(callResult.isError());
        assertEquals(List.of(new McpSchema.TextContent("chain failed")), callResult.content());
    }

    @Test
    void callHandlerAnswersExceptionClassWhenExceptionHasNoMessage() {
        Exchange result = new DefaultExchange(new DefaultCamelContext());
        result.setException(new IllegalStateException());

        McpSchema.CallToolResult callResult = callTool(result);

        assertTrue(callResult.isError());
        assertEquals(List.of(new McpSchema.TextContent("java.lang.IllegalStateException")), callResult.content());
    }

    @Test
    void callHandlerSendsTheCallToTheServingDeploymentWhenThisContextStoppedFirst() {
        // Run 40467794883: a redeploy replaced the tool and stopped this context before the call reached the trigger.
        Exchange stopped = new DefaultExchange(new DefaultCamelContext());
        stopped.setException(new DirectConsumerNotAvailableException("No consumers available on endpoint", stopped));
        Exchange served = new DefaultExchange(new DefaultCamelContext());
        served.getMessage().setBody("{\"called\":\"v2\"}");
        SpringCamelContext serving = contextAnswering("direct:element-v2", served);
        toolTargets.publish(TOOL_NAME, new McpToolTargets.Target(serving, "direct:element-v2"));

        McpSchema.CallToolResult callResult = callTool(stopped);

        assertFalse(callResult.isError());
        assertEquals(List.of(new McpSchema.TextContent("{\"called\":\"v2\"}")), callResult.content());
    }

    @Test
    void callHandlerAnswersTheStoppedTriggerWhenNoOtherDeploymentServesTheTool() {
        Exchange stopped = new DefaultExchange(new DefaultCamelContext());
        stopped.setException(new DirectConsumerNotAvailableException("No consumers available on endpoint", stopped));

        McpSchema.CallToolResult callResult = callTool(stopped);

        assertTrue(callResult.isError());
        assertEquals(List.of(new McpSchema.TextContent(stopped.getException().getMessage())), callResult.content());
    }

    @Test
    void callHandlerClosesTheProducerTemplateOfEachCall() throws Exception {
        ProducerTemplate producerTemplate = mock(ProducerTemplate.class);
        when(context.createProducerTemplate()).thenReturn(producerTemplate);
        when(producerTemplate.request(eq("direct:" + ELEMENT_ID), any(Processor.class)))
                .thenReturn(new DefaultExchange(new DefaultCamelContext()));
        registrar.apply(context, mcpTriggerProperties(null), deploymentInfo());

        preparedTool().callHandler().apply(null, new McpSchema.CallToolRequest(TOOL_NAME, Map.of()));

        verify(producerTemplate).close();
    }

    @Test
    void applyPreparesTheTargetTheToolCalls() {
        registrar.apply(context, mcpTriggerProperties(null), deploymentInfo());

        Map<?, ?> targets = context.getRegistry().lookupByNameAndType(McpToolRegistrar.TOOL_TARGETS, Map.class);
        assertEquals(Map.of(TOOL_NAME, new McpToolTargets.Target(context, "direct:" + ELEMENT_ID)), targets);
    }

    @Test
    void executeAppliesOnlyMcpTriggerElements() {
        ElementProperties mcpElement = mcpTriggerProperties(null);
        ElementProperties httpElement = elementProperties("http-trigger");
        httpElement.getProperties().put("name", "http-tool");
        DeploymentConfiguration config = DeploymentConfiguration.builder()
                .properties(List.of(mcpElement, httpElement))
                .build();
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.execute(context, deploymentInfo, config);

        assertEquals(1, preparedTools().size());
    }

    @Test
    void applyCollectsEveryToolOfTheChain() {
        registrar.apply(context, mcpTriggerProperties(null), deploymentInfo());
        registrar.apply(context, mcpTriggerProperties(OUTPUT_SCHEMA), deploymentInfo());

        assertEquals(2, preparedTools().size());
    }

    @SuppressWarnings("unchecked")
    private List<McpServerFeatures.SyncToolSpecification> preparedTools() {
        return context.getRegistry().lookupByNameAndType(McpToolRegistrar.TOOL_SPECIFICATIONS, List.class);
    }

    private McpServerFeatures.SyncToolSpecification preparedTool() {
        List<McpServerFeatures.SyncToolSpecification> tools = preparedTools();
        assertEquals(1, tools.size());
        return tools.get(0);
    }

    private McpSchema.CallToolResult callTool(Exchange result) {
        ProducerTemplate producerTemplate = mock(ProducerTemplate.class);
        when(context.createProducerTemplate()).thenReturn(producerTemplate);
        when(producerTemplate.request(eq("direct:" + ELEMENT_ID), any(Processor.class))).thenReturn(result);

        registrar.apply(context, mcpTriggerProperties(null), deploymentInfo());

        return preparedTool().callHandler()
                .apply(null, new McpSchema.CallToolRequest(TOOL_NAME, Map.of("param", "value")));
    }

    private SpringCamelContext contextAnswering(String endpointUri, Exchange result) {
        SpringCamelContext other = mock(SpringCamelContext.class);
        ProducerTemplate producerTemplate = mock(ProducerTemplate.class);
        when(other.createProducerTemplate()).thenReturn(producerTemplate);
        when(producerTemplate.request(eq(endpointUri), any(Processor.class))).thenReturn(result);
        return other;
    }

    private ElementProperties elementProperties(String elementType) {
        Map<String, String> props = new HashMap<>();
        props.put(ChainProperties.ELEMENT_TYPE, elementType);
        props.put(ChainProperties.ELEMENT_ID, ELEMENT_ID);
        return ElementProperties.builder()
                .elementId(ELEMENT_ID)
                .properties(props)
                .build();
    }

    private ElementProperties mcpTriggerProperties(String outputSchema) {
        Map<String, String> props = new HashMap<>();
        props.put(ChainProperties.ELEMENT_TYPE, "mcp-trigger");
        props.put(ChainProperties.ELEMENT_ID, ELEMENT_ID);
        props.put("name", TOOL_NAME);
        props.put("description", TOOL_DESCRIPTION);
        props.put("title", TOOL_TITLE);
        props.put("readOnly", "true");
        props.put("destructive", "false");
        props.put("idempotent", "true");
        props.put("openWorld", "false");
        props.put("inputSchema", INPUT_SCHEMA);
        if (outputSchema != null) {
            props.put("outputSchema", outputSchema);
        }
        return ElementProperties.builder()
                .elementId(ELEMENT_ID)
                .properties(props)
                .build();
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder()
                .deploymentId(DEPLOYMENT_ID)
                .chainId("chain-1")
                .build();
    }
}
