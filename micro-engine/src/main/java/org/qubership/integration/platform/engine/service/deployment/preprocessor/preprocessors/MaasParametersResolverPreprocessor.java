package org.qubership.integration.platform.engine.service.deployment.preprocessor.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.maas.MaasService;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessor;

import java.net.URISyntaxException;

@ApplicationScoped
@Priority(2)
public class MaasParametersResolverPreprocessor implements DeploymentPreprocessor {
    private final MaasService maasService;

    @Inject
    public MaasParametersResolverPreprocessor(MaasService maasService) {
        this.maasService = maasService;
    }

    @Override
    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception {
        return resolveMaasParameters(maasService, deploymentUpdate);
    }

    private static DeploymentUpdate resolveMaasParameters(
            MaasService service,
            DeploymentUpdate deploymentUpdate
    ) throws URISyntaxException {
        DeploymentConfiguration configuration = deploymentUpdate.getConfiguration();
        String configurationXml = configuration.getXml();
        configurationXml = service.resolveDeploymentMaasParameters(configuration, configurationXml);
        return deploymentUpdate.toBuilder()
                .configuration(
                        configuration.toBuilder()
                                .xml(configurationXml)
                                .build()
                ).build();
    }
}
