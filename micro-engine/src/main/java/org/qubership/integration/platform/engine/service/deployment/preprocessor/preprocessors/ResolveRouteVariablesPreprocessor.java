package org.qubership.integration.platform.engine.service.deployment.preprocessor.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessor;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.RegisterRoutesInControlPlaneAction;

import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@ApplicationScoped
@Priority(1)
public class ResolveRouteVariablesPreprocessor implements DeploymentPreprocessor {
    @Override
    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception {
        DeploymentConfiguration configuration = deploymentUpdate.getConfiguration();
        String configurationXml = configuration.getXml();
        configurationXml = resolveRouteVariables(configuration.getRoutes(), configurationXml);
        return deploymentUpdate.toBuilder()
                .configuration(
                        configuration.toBuilder()
                                .xml(configurationXml)
                                .build()
                ).build();
    }

    private String resolveRouteVariables(List<DeploymentRouteUpdate> routes, String text) {
        String result = text;

        for (DeploymentRouteUpdate route : routes) {
            DeploymentRouteUpdate tempRoute =
                    RegisterRoutesInControlPlaneAction.formatServiceRoutes(route);

            RouteType type = tempRoute.getType();
            if (nonNull(tempRoute.getVariableName())
                    && (RouteType.EXTERNAL_SENDER == type || RouteType.EXTERNAL_SERVICE == type)) {
                String variablePlaceholder = String.format("%%%%{%s}", tempRoute.getVariableName());
                String gatewayPrefix = tempRoute.getGatewayPrefix();
                result = result.replace(variablePlaceholder,
                        isNull(gatewayPrefix) ? "" : gatewayPrefix);
            }
        }
        return result;
    }
}
