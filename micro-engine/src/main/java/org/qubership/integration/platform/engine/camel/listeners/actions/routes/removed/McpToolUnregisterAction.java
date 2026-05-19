package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteRemoved;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.qubership.integration.platform.engine.camel.listeners.actions.routes.added.McpToolRegistrar.DEPLOYMENT_ID;

@ApplicationScoped
@OnRouteRemoved
public class McpToolUnregisterAction implements EventProcessingAction<CamelEvent.RouteRemovedEvent> {
    @Inject
    ToolManager toolManager;

    @Override
    public void process(CamelEvent.RouteRemovedEvent event) throws Exception {
        Route route = event.getRoute();
        DeploymentInfo deploymentInfo = MetadataUtil.getBean(event.getRoute(), DeploymentInfo.class);
        List<ToolManager.ToolInfo> toolsToRemove = StreamSupport.stream(toolManager.spliterator(), false)
                .filter(tool -> deploymentInfo.getId()
                        .equals(tool.metadata().get(MetaKey.of(DEPLOYMENT_ID))))
                .toList();
        toolsToRemove.forEach(tool -> toolManager.removeTool(tool.name()));
    }
}
