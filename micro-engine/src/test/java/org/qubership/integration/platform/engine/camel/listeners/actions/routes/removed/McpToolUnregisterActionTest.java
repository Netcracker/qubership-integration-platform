package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ToolManager;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;
import java.util.Spliterator;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.camel.listeners.actions.routes.added.McpToolRegistrar.DEPLOYMENT_ID;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class McpToolUnregisterActionTest {

    private static final String DEPLOYMENT_INFO_ID = "deployment-id";
    private static final String ANOTHER_DEPLOYMENT_INFO_ID = "another-deployment-id";
    private static final String TOOL_NAME = "tool-name";
    private static final String ANOTHER_TOOL_NAME = "another-tool-name";

    @Mock
    private ToolManager toolManager;

    @Mock
    private CamelEvent.RouteRemovedEvent event;

    @Mock
    private Route route;

    @Mock
    private DeploymentInfo deploymentInfo;

    private McpToolUnregisterAction action;

    @BeforeEach
    void setUp() {
        action = new McpToolUnregisterAction();
        action.toolManager = toolManager;

        when(event.getRoute()).thenReturn(route);
    }

    @Test
    void shouldRemoveToolWhenDeploymentIdMatches() throws Exception {
        ToolManager.ToolInfo toolInfo = toolInfoForDeployment(TOOL_NAME, DEPLOYMENT_INFO_ID);

        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(toolInfo));
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verify(toolManager).removeTool(TOOL_NAME);
        }
    }

    @Test
    void shouldRemoveAllToolsWhenDeploymentIdMatchesMultipleTools() throws Exception {
        ToolManager.ToolInfo toolInfo = toolInfoForDeployment(TOOL_NAME, DEPLOYMENT_INFO_ID);
        ToolManager.ToolInfo anotherToolInfo = toolInfoForDeployment(ANOTHER_TOOL_NAME, DEPLOYMENT_INFO_ID);

        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(toolInfo, anotherToolInfo));
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verify(toolManager).removeTool(TOOL_NAME);
            verify(toolManager).removeTool(ANOTHER_TOOL_NAME);
        }
    }

    @Test
    void shouldSkipToolsWhenDeploymentIdDoesNotMatch() throws Exception {
        ToolManager.ToolInfo toolInfo = toolInfoForAnotherDeployment();

        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(toolInfo));
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verify(toolManager, never()).removeTool(any());
        }
    }

    @Test
    void shouldSkipToolsWithoutDeploymentIdMetadata() throws Exception {
        ToolManager.ToolInfo toolInfo = toolInfoWithoutDeploymentId();

        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(toolInfo));
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verify(toolManager, never()).removeTool(any());
        }
    }

    @Test
    void shouldNotRemoveAnythingWhenToolManagerDoesNotContainTools() throws Exception {
        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator());

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verify(toolManager, never()).removeTool(any());
        }
    }

    @Test
    void shouldPropagateExceptionWhenToolRemovalFails() {
        RuntimeException exception = new RuntimeException("Failed to remove MCP tool");
        ToolManager.ToolInfo toolInfo = toolInfoForDeployment(TOOL_NAME, DEPLOYMENT_INFO_ID);

        when(toolManager.spliterator()).thenReturn(toolInfoSpliterator(toolInfo));
        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_INFO_ID);
        doThrow(exception).when(toolManager).removeTool(TOOL_NAME);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

            assertSame(exception, result);
        }
    }

    private static ToolManager.ToolInfo toolInfoForDeployment(String toolName, String deploymentId) {
        ToolManager.ToolInfo toolInfo = mock(ToolManager.ToolInfo.class);
        when(toolInfo.metadata()).thenReturn(Map.of(MetaKey.of(DEPLOYMENT_ID), deploymentId));
        when(toolInfo.name()).thenReturn(toolName);
        return toolInfo;
    }

    private static ToolManager.ToolInfo toolInfoForAnotherDeployment() {
        ToolManager.ToolInfo toolInfo = mock(ToolManager.ToolInfo.class);
        when(toolInfo.metadata()).thenReturn(Map.of(MetaKey.of(DEPLOYMENT_ID), ANOTHER_DEPLOYMENT_INFO_ID));
        return toolInfo;
    }

    private static ToolManager.ToolInfo toolInfoWithoutDeploymentId() {
        ToolManager.ToolInfo toolInfo = mock(ToolManager.ToolInfo.class);
        when(toolInfo.metadata()).thenReturn(Map.of());
        return toolInfo;
    }

    @SafeVarargs
    private static Spliterator<ToolManager.ToolInfo> toolInfoSpliterator(ToolManager.ToolInfo... items) {
        return List.of(items).spliterator();
    }
}
