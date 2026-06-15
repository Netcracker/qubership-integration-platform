package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.McpTriggerInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.camel.listeners.actions.routes.added.McpToolRegistrar.DEPLOYMENT_ID;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class McpToolRegistrarTest {

    private static final String DEPLOYMENT_INFO_ID = "deployment-id";
    private static final String ELEMENT_ID = "mcp-trigger-element-id";
    private static final String MCP_TRIGGER_TYPE = "mcp-trigger";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string"
                }
              }
            }
            """;
    private static final String OUTPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "result": {
                  "type": "string"
                }
              }
            }
            """;

    @Mock
    private ToolManager toolManager;

    @Mock(answer = Answers.RETURNS_SELF)
    private ToolManager.ToolDefinition toolDefinition;

    @Mock
    private CamelEvent.RouteAddedEvent event;

    @Mock
    private Route route;

    @Mock
    private CamelContext camelContext;

    @Mock
    private ProducerTemplate producerTemplate;

    @Mock
    private Exchange resultExchange;

    @Mock
    private Message resultMessage;

    @Mock
    private ToolManager.ToolArguments toolArguments;

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private ElementInfo elementInfo;

    @Mock
    private McpTriggerInfo mcpTriggerInfo;

    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new McpToolRegistrar();
        registrar.toolManager = toolManager;
        registrar.objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSkipRegistrationWhenDeploymentToolAlreadyRegistered() throws Exception {
        ToolManager.ToolInfo registeredTool = registeredTool(DEPLOYMENT_INFO_ID);

        when(event.getRoute()).thenReturn(route);
        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(registeredTool));

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);

            registrar.process(event);

            verify(toolManager, never()).newTool(any());
            metadataUtil.verify(() -> MetadataUtil.getBean(route, DeploymentInfo.class));
            metadataUtil.verifyNoMoreInteractions();
        }
    }

    @Test
    void shouldRegisterMcpToolForMcpTriggerWhenDeploymentIsNotRegistered() throws Exception {
        when(event.getRoute()).thenReturn(route);
        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator());

        stubMcpTriggerElement();
        stubMcpTriggerInfo();
        stubToolDefinition();

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(elementInfo));
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(route, ELEMENT_ID, McpTriggerInfo.class))
                .thenReturn(mcpTriggerInfo);

            registrar.process(event);

            verify(toolManager).newTool("tool-name");
            verify(toolDefinition).register();
        }
    }

    @Test
    void shouldRegisterToolDefinitionWithInputAndOutputSchemas() throws Exception {
        stubMcpTriggerInfo();
        stubToolDefinition();

        registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo);

        verify(toolManager).newTool("tool-name");
        verify(toolDefinition).setDescription("tool-description");
        verify(toolDefinition).setTitle("tool-title");
        verify(toolDefinition).setInputSchema(registrar.objectMapper.readTree(INPUT_SCHEMA));
        verify(toolDefinition).setOutputSchema(registrar.objectMapper.readTree(OUTPUT_SCHEMA));
        verify(toolDefinition).setMetadata(Map.of(MetaKey.of(DEPLOYMENT_ID), DEPLOYMENT_INFO_ID));
        verify(toolDefinition).register();
    }

    @Test
    void shouldRegisterToolDefinitionWithoutOutputSchemaWhenOutputSchemaIsBlank() throws Exception {
        stubMcpTriggerInfoWithBlankOutputSchema();
        stubToolDefinition();

        registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo);

        verify(toolDefinition).setInputSchema(registrar.objectMapper.readTree(INPUT_SCHEMA));
        verify(toolDefinition, never()).setOutputSchema(any());
        verify(toolDefinition).register();
    }

    @Test
    void shouldWrapJsonProcessingExceptionWhenInputSchemaIsInvalid() {
        stubMcpTriggerInfoWithInvalidInputSchema();
        stubToolDefinition();

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo)
        );

        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldCreateAnnotationsFromMcpTriggerInfo() {
        stubMcpTriggerInfo();
        stubToolDefinition();

        registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo);

        ArgumentCaptor<ToolManager.ToolAnnotations> annotationsCaptor =
            ArgumentCaptor.forClass(ToolManager.ToolAnnotations.class);
        verify(toolDefinition).setAnnotations(annotationsCaptor.capture());

        ToolManager.ToolAnnotations annotations = annotationsCaptor.getValue();
        assertEquals("tool-title", annotations.title());
        assertEquals(Boolean.TRUE, annotations.readOnlyHint());
        assertEquals(Boolean.FALSE, annotations.destructiveHint());
        assertEquals(Boolean.TRUE, annotations.idempotentHint());
        assertEquals(Boolean.FALSE, annotations.openWorldHint());
    }

    @Test
    void shouldCallDirectEndpointAndReturnStructuredSuccessWhenOutputSchemaExists() throws Exception {
        Map<String, Object> arguments = Map.of("name", "test-name");
        Map<String, Object> responseBody = Map.of("result", "ok");

        stubMcpTriggerInfo();
        stubMcpTriggerElementSnapshotIdOnly();
        stubToolDefinition();

        Exchange requestExchange = stubHandlerCamelInteraction(arguments);
        when(resultMessage.getBody()).thenReturn(responseBody);

        registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo);

        Function<ToolManager.ToolArguments, ToolResponse> handler = captureHandler();

        ToolResponse response = handler.apply(toolArguments);

        assertNotNull(response);
        assertEquals(arguments, requestExchange.getIn().getBody());
        verify(camelContext).createProducerTemplate();
        verify(producerTemplate).request(eq("direct:" + ELEMENT_ID), any(Processor.class));
        verify(resultMessage).getBody();
    }

    @Test
    void shouldCallDirectEndpointAndReturnStringSuccessWhenOutputSchemaIsBlank() throws Exception {
        Map<String, Object> arguments = Map.of("name", "test-name");

        stubMcpTriggerInfoWithBlankOutputSchema();
        stubMcpTriggerElementSnapshotIdOnly();
        stubToolDefinition();

        Exchange requestExchange = stubHandlerCamelInteraction(arguments);
        when(resultMessage.getBody(String.class)).thenReturn("plain result");

        registrar.registerMcpTool(route, deploymentInfo, elementInfo, mcpTriggerInfo);

        Function<ToolManager.ToolArguments, ToolResponse> handler = captureHandler();

        ToolResponse response = handler.apply(toolArguments);

        assertNotNull(response);
        assertEquals(arguments, requestExchange.getIn().getBody());
        verify(camelContext).createProducerTemplate();
        verify(producerTemplate).request(eq("direct:" + ELEMENT_ID), any(Processor.class));
        verify(resultMessage).getBody(String.class);
    }

    private void stubToolDefinition() {
        when(toolManager.newTool("tool-name")).thenReturn(toolDefinition);
    }

    private void stubMcpTriggerElement() {
        when(elementInfo.getType()).thenReturn(MCP_TRIGGER_TYPE);
        when(elementInfo.getSnapshotElementId()).thenReturn(ELEMENT_ID);
    }

    private void stubMcpTriggerElementSnapshotIdOnly() {
        when(elementInfo.getSnapshotElementId()).thenReturn(ELEMENT_ID);
    }

    private void stubMcpTriggerInfo() {
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);
        when(mcpTriggerInfo.getName()).thenReturn("tool-name");
        when(mcpTriggerInfo.getDescription()).thenReturn("tool-description");
        when(mcpTriggerInfo.getTitle()).thenReturn("tool-title");
        when(mcpTriggerInfo.getReadOnly()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getDestructive()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getIdempotent()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getOpenWorld()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getInputSchema()).thenReturn(INPUT_SCHEMA);
        when(mcpTriggerInfo.getOutputSchema()).thenReturn(OUTPUT_SCHEMA);
    }

    private void stubMcpTriggerInfoWithBlankOutputSchema() {
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);
        when(mcpTriggerInfo.getName()).thenReturn("tool-name");
        when(mcpTriggerInfo.getDescription()).thenReturn("tool-description");
        when(mcpTriggerInfo.getTitle()).thenReturn("tool-title");
        when(mcpTriggerInfo.getReadOnly()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getDestructive()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getIdempotent()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getOpenWorld()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getInputSchema()).thenReturn(INPUT_SCHEMA);
        when(mcpTriggerInfo.getOutputSchema()).thenReturn(" ");
    }

    private void stubMcpTriggerInfoWithInvalidInputSchema() {
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);
        when(mcpTriggerInfo.getName()).thenReturn("tool-name");
        when(mcpTriggerInfo.getDescription()).thenReturn("tool-description");
        when(mcpTriggerInfo.getTitle()).thenReturn("tool-title");
        when(mcpTriggerInfo.getReadOnly()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getDestructive()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getIdempotent()).thenReturn(Boolean.TRUE);
        when(mcpTriggerInfo.getOpenWorld()).thenReturn(Boolean.FALSE);
        when(mcpTriggerInfo.getInputSchema()).thenReturn("{ invalid json");
    }

    private Exchange stubHandlerCamelInteraction(Map<String, Object> arguments) throws Exception {
        Exchange requestExchange = new DefaultExchange(camelContext);

        when(route.getCamelContext()).thenReturn(camelContext);
        when(route.getId()).thenReturn("route-id");
        when(camelContext.createProducerTemplate()).thenReturn(producerTemplate);
        when(toolArguments.args()).thenReturn(arguments);

        when(resultExchange.getMessage()).thenReturn(resultMessage);

        doAnswer(invocation -> {
            Processor processor = invocation.getArgument(1);
            processor.process(requestExchange);
            return resultExchange;
        }).when(producerTemplate).request(eq("direct:" + ELEMENT_ID), any(Processor.class));

        return requestExchange;
    }

    @SuppressWarnings("unchecked")
    private Function<ToolManager.ToolArguments, ToolResponse> captureHandler() {
        ArgumentCaptor<Function<ToolManager.ToolArguments, ToolResponse>> handlerCaptor =
            ArgumentCaptor.forClass(Function.class);
        verify(toolDefinition).setHandler(handlerCaptor.capture());
        return handlerCaptor.getValue();
    }

    private static ToolManager.ToolInfo registeredTool(String deploymentId) {
        ToolManager.ToolInfo toolInfo = mock(ToolManager.ToolInfo.class);
        when(toolInfo.metadata()).thenReturn(Map.of(MetaKey.of(DEPLOYMENT_ID), deploymentId));
        return toolInfo;
    }

    @SafeVarargs
    private static Spliterator<ToolManager.ToolInfo> toolInfoSpliterator(ToolManager.ToolInfo... items) {
        return List.of(items).spliterator();
    }
}
