package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    private McpSyncServer mcpSyncServer;
    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        mcpSyncServer = mock(McpSyncServer.class);
        registrar = new McpToolRegistrar(mcpSyncServer, new ObjectMapper());
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
    void applyRegistersToolWithMcpServer() {
        ElementProperties properties = mcpTriggerProperties(null);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(null, properties, deploymentInfo);

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer).addTool(captor.capture());

        McpSchema.Tool tool = captor.getValue().tool();
        assertEquals(TOOL_NAME, tool.name());
        assertEquals(TOOL_DESCRIPTION, tool.description());
        assertEquals(TOOL_TITLE, tool.title());
        assertEquals(DEPLOYMENT_ID, tool.meta().get(McpToolRegistrar.DEPLOYMENT_ID));
    }

    @Test
    void applyRegistersToolWithOutputSchemaWhenProvided() {
        ElementProperties properties = mcpTriggerProperties(OUTPUT_SCHEMA);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(null, properties, deploymentInfo);

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer).addTool(captor.capture());

        McpSchema.Tool tool = captor.getValue().tool();
        assertNotNull(tool.outputSchema());
    }

    @Test
    void applyRegistersToolWithoutOutputSchemaWhenBlank() {
        ElementProperties properties = mcpTriggerProperties("");
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(null, properties, deploymentInfo);

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer).addTool(captor.capture());

        McpSchema.Tool tool = captor.getValue().tool();
        assertNull(tool.outputSchema());
    }

    @Test
    void applyRegistersToolWithCorrectAnnotations() {
        ElementProperties properties = mcpTriggerProperties(null);
        DeploymentInfo deploymentInfo = deploymentInfo();

        registrar.apply(null, properties, deploymentInfo);

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer).addTool(captor.capture());

        McpSchema.ToolAnnotations annotations = captor.getValue().tool().annotations();
        assertNotNull(annotations);
        assertTrue(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
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

        registrar.execute(null, deploymentInfo, config);

        verify(mcpSyncServer, times(1)).addTool(any());
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
        props.put("requiresLocal", "false");
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
