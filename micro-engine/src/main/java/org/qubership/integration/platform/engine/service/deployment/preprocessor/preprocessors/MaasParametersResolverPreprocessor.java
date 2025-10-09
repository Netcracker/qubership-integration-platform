package org.qubership.integration.platform.engine.service.deployment.preprocessor.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.MaasService;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessor;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.net.URISyntaxException;
import java.util.Optional;

@ApplicationScoped
@Priority(2)
public class MaasParametersResolverPreprocessor implements DeploymentPreprocessor {
    private final Optional<MaasService> maasService;

    @Inject
    public MaasParametersResolverPreprocessor(Instance<MaasService> maasService) {
        this.maasService = InjectUtil.injectOptional(maasService);
    }

    @Override
    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception {
        return maasService.isPresent()
                ? resolveMaasParameters(maasService.get(), deploymentUpdate)
                : deploymentUpdate;
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
