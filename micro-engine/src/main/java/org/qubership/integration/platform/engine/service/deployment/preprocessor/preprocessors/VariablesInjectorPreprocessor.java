package org.qubership.integration.platform.engine.service.deployment.preprocessor.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessor;

@ApplicationScoped
@Priority(3)
public class VariablesInjectorPreprocessor implements DeploymentPreprocessor {
    private final VariablesService variablesService;

    @Inject
    public VariablesInjectorPreprocessor(VariablesService variablesService) {
        this.variablesService = variablesService;
    }

    @Override
    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) {
        String configurationXml = variablesService.injectVariables(
                deploymentUpdate.getConfiguration().getXml(), true);
        return deploymentUpdate.toBuilder()
                .configuration(
                        deploymentUpdate.getConfiguration().toBuilder()
                                .xml(configurationXml)
                                .build()
                ).build();
    }
}
