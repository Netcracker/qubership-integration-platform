/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.deployment.processing;

import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RouteDefinitionHelper;
import org.apache.camel.model.RoutesDefinition;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.camel.metadata.MetadataService;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterRoutesCreated;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterRoutesDeleted;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesDeleted;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.apache.camel.xml.jaxb.JaxbHelper.loadRoutesDefinition;

@Slf4j
@ApplicationScoped
public class DeploymentProcessingService {
    @Inject
    CamelContext camelContext;

    @Inject
    MetadataService metadataService;

    @Inject
    @All
    @OnBeforeRoutesCreated
    List<DeploymentProcessingAction> beforeRoutesCreatedActions;

    @Inject
    @All
    @OnAfterRoutesCreated
    List<DeploymentProcessingAction> afterRoutesCreatedActions;

    @Inject
    @All
    @OnBeforeRoutesDeleted
    List<DeploymentProcessingAction> beforeRoutesDeletedActions;

    @Inject
    @All
    @OnAfterRoutesDeleted
    List<DeploymentProcessingAction> afterRoutesDeletedActions;

    public void deploy(
            DeploymentUpdate deploymentUpdate,
            boolean startRoutes
    ) throws Exception {
        try {
            doDeploy((DefaultCamelContext) camelContext, deploymentUpdate, startRoutes);
        } catch (Exception exception) {
            String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
            log.error("Failed to process deployment {}", deploymentId, exception);
            undeploy(deploymentUpdate);
            throw exception;
        }
    }

    public void undeploy(DeploymentUpdate deploymentUpdate) throws Exception {
        try {
            doUndeploy((DefaultCamelContext) camelContext, deploymentUpdate);
        } catch (Exception exception) {
            log.error("Failed to undeploy {}",
                    deploymentUpdate.getDeploymentInfo().getDeploymentId(), exception);
            throw exception;
        }
    }

    public void startRoutes(String deploymentId) throws Exception {
        doStartRoutes((DefaultCamelContext) camelContext, deploymentId);
    }

    private void doStartRoutes(DefaultCamelContext context, String deploymentId) throws Exception {
        Collection<String> routeIds = getDeploymentRoutes(context, deploymentId).map(Route::getId).toList();
        for (String routeId : routeIds) {
            context.startRoute(routeId);
        }
    }

    public boolean hasDeployment(String deploymentId) {
        return camelContext.getRoutes().stream()
                .map(metadataService::getMetadata)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(Metadata::getDeploymentId)
                .anyMatch(deploymentId::equals);
    }

    private void doDeploy(
            DefaultCamelContext context,
            DeploymentUpdate deploymentUpdate,
            boolean startRoutes
    ) throws Exception {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        log.debug("Processing deployment {}", deploymentId);

        if (hasDeployment(deploymentId)) {
            log.warn("Deployment {} already exists", deploymentId);
            return;
        }

        log.debug("Performing actions before creation of routes for deployment {}", deploymentId);
        executeActions(beforeRoutesCreatedActions, context, deploymentUpdate);

        log.debug("Creating routes for deployment {}", deploymentId);
        RoutesDefinition routesDefinition = loadRoutes(context, deploymentUpdate);

        log.debug("Performing actions after creation of routes for deployment {}", deploymentId);
        executeActions(afterRoutesCreatedActions, context, deploymentUpdate);

        if (startRoutes) {
            for (RouteDefinition routeDefinition : routesDefinition.getRoutes()) {
                context.startRoute(routeDefinition.getRouteId());
            }
        }

        log.debug("Deployment processed {}", deploymentId);
    }

    private void doUndeploy(
            DefaultCamelContext context,
            DeploymentUpdate deploymentUpdate
    ) throws Exception {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        log.debug("Performing undeploy of {}", deploymentId);

        log.debug("Performing actions before deletion of routes for deployment {}", deploymentId);
        executeActions(beforeRoutesDeletedActions, context, deploymentUpdate);

        log.debug("Deleting routes for deployment {}", deploymentId);
        stopAndRemoveDeploymentRoutes(context, deploymentUpdate);

        log.debug("Performing actions before deletion of routes for deployment {}", deploymentId);
        executeActions(afterRoutesDeletedActions, context, deploymentUpdate);

        log.debug("Undeployment done for {}", deploymentId);
    }

    private void stopAndRemoveDeploymentRoutes(
            DefaultCamelContext context,
            DeploymentUpdate deploymentUpdate
    ) throws Exception {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        Collection<RouteDefinition> routeDefinitions = getDeploymentRoutes(context, deploymentId)
                .map(Route::getRoute)
                .map(RouteDefinition.class::cast)
                .toList();
        context.removeRouteDefinitions(routeDefinitions);
    }

    private Stream<Route> getDeploymentRoutes(DefaultCamelContext context, String deploymentId) {
        return context.getRoutes().stream()
                .filter(route -> metadataService.getMetadata(route)
                        .map(Metadata::getDeploymentId)
                        .map(deploymentId::equals)
                        .orElse(false));
    }

    private RoutesDefinition loadRoutes(
            DefaultCamelContext context,
            DeploymentUpdate deploymentUpdate
    ) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Loading routes from: \n{}", deploymentUpdate.getConfiguration().getXml());
        }

        byte[] configurationBytes = deploymentUpdate.getConfiguration().getXml().getBytes();
        ByteArrayInputStream configInputStream = new ByteArrayInputStream(configurationBytes);
        RoutesDefinition routesDefinition = loadRoutesDefinition(context, configInputStream);

        Metadata metadata = Metadata.builder()
                .deploymentId(deploymentUpdate.getDeploymentInfo().getDeploymentId())
                .build();
        routesDefinition.getRoutes().forEach(route ->
                metadataService.setMetadata(route, metadata));

        // XML routes must be marked as unprepared as camel-core
        // must do special handling for XML DSL
        for (RouteDefinition route : routesDefinition.getRoutes()) {
            RouteDefinitionHelper.prepareRoute(context, route);
            route.markPrepared();
        }
        routesDefinition.getRoutes().forEach(RouteDefinition::markUnprepared);

        context.addRouteDefinitions(routesDefinition.getRoutes());

        return routesDefinition;
    }

    private void executeActions(
            Collection<DeploymentProcessingAction> actions,
            CamelContext context,
            DeploymentUpdate deploymentUpdate
    ) {
        actions.forEach(action -> executeAction(action, context, deploymentUpdate));
    }

    private void executeAction(
            DeploymentProcessingAction action,
            CamelContext context,
            DeploymentUpdate deploymentUpdate
    ) {
        log.debug("Applying deployment processing action {} for deployment {}",
                action.getClass().getSimpleName(), deploymentUpdate.getDeploymentInfo().getDeploymentId());
        action.execute(context, deploymentUpdate);
    }
}
