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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import static java.util.Objects.nonNull;

import java.net.MalformedURLException;
import java.util.List;

import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.qubership.integration.platform.engine.util.SimpleHttpUriUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ControlPlaneService.class)
@OnBeforeDeploymentContextCreated
public class RegisterRoutesInControlPlaneAction implements DeploymentProcessingAction {
    private final VariablesService variablesService;
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;

    @Autowired
    public RegisterRoutesInControlPlaneAction(
        VariablesService variablesService,
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration
    ) {
        this.variablesService = variablesService;
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
    }

    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        resolveVariablesInRoutes(deploymentConfiguration);

        // external triggers routes
        List<DeploymentRouteUpdate> gatewayTriggersRoutes = deploymentConfiguration
            .getRoutes().stream()
            .filter(route -> RouteType.triggerRouteWithGateway(route.getType()))
            .peek(externalRoute -> externalRoute
                    .setPath("/" + StringUtils.strip(externalRoute.getPath(), "/")))
            .toList();

        try {
            controlPlaneService.postPublicEngineRoutes(
                gatewayTriggersRoutes.stream()
                    .filter(route -> RouteType.isPublicTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getDeploymentName());
            controlPlaneService.postPrivateEngineRoutes(
                gatewayTriggersRoutes.stream()
                    .filter(route -> RouteType.isPrivateTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getDeploymentName());

            // cleanup triggers routes if necessary (for internal triggers)
            controlPlaneService.removeEngineRoutesByPathsAndEndpoint(
                deploymentConfiguration.getRoutes().stream()
                    .filter(route -> RouteType.triggerRouteCleanupNeeded(route.getType()))
                    .map(route -> Pair.of(route.getPath(), route.getType()))
                    .toList(),
                applicationConfiguration.getDeploymentName());

            // Register http based senders and service call paths '/{senderType}/{elementId}', '/system/{elementId}'
            deploymentConfiguration.getRoutes().stream()
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                    || route.getType() == RouteType.EXTERNAL_SERVICE)
                .forEach(route -> controlPlaneService.postEgressGatewayRoutes(formatServiceRoutes(route)));
        } catch (ControlPlaneException e) {
            throw new DeploymentRetriableException(e);
        }
    }

    private void resolveVariablesInRoutes(DeploymentConfiguration deploymentConfiguration) {
        deploymentConfiguration.getRoutes().stream()
            .filter(route -> nonNull(route.getVariableName())
                && (RouteType.EXTERNAL_SENDER == route.getType()
                || RouteType.EXTERNAL_SERVICE == route.getType()))
            .filter(route -> variablesService.hasVariableReferences(route.getPath()))
            .forEach(route -> route.setPath(variablesService.injectVariables(route.getPath())));
    }

    public static @NotNull DeploymentRouteUpdate formatServiceRoutes(DeploymentRouteUpdate route) {
        DeploymentRouteUpdate routeUpdate = route;

        // add hash to route
        if (nonNull(routeUpdate.getVariableName())
                && RouteType.EXTERNAL_SERVICE.equals(routeUpdate.getType())) {
            routeUpdate = routeUpdate.toBuilder().build();
            // Formatting URI (add protocol if needed)
            try {
                routeUpdate.setPath(SimpleHttpUriUtils.formatUri(routeUpdate.getPath()));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            // Adding hash to gateway prefix
            String pathHash = getCPRouteHash(routeUpdate);
            if (!StringUtils.isBlank(pathHash)) {
                routeUpdate.setGatewayPrefix(routeUpdate.getGatewayPrefix() + '/' + pathHash);
            }
        }

        return routeUpdate;
    }

    private static String getCPRouteHash(DeploymentRouteUpdate route) {
        if (route.getPath() == null) {
            return null;
        }

        // Add all parameters that will be sent to control-plane
        String strToHash = StringUtils.joinWith(",",
                route.getPath(),
                route.getConnectTimeout()
        );

        return DigestUtils.sha1Hex(strToHash);
    }
}
