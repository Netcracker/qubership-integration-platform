package org.qubership.integration.platform.engine.service.deployment.preprocessor.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessor;
import org.qubership.integration.platform.engine.service.xmlpreprocessor.XmlConfigurationPreProcessor;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Optional;

@ApplicationScoped
@Priority(0)
public class XmlConfigurationPreprocessor implements DeploymentPreprocessor {
    private final Optional<XmlConfigurationPreProcessor> preprocessor;

    @Inject
    public XmlConfigurationPreprocessor(Instance<XmlConfigurationPreProcessor> preprocessor) {
        this.preprocessor = InjectUtil.injectOptional(preprocessor);
    }

    @Override
    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception {
        return preprocessor.map(p -> {
            DeploymentConfiguration configuration = deploymentUpdate.getConfiguration();
            String configurationXml = configuration.getXml();
            configurationXml = p.process(configurationXml);
            return deploymentUpdate.toBuilder()
                    .configuration(
                            configuration.toBuilder()
                                    .xml(configurationXml)
                                    .build()
                    ).build();
        }).orElse(deploymentUpdate);
    }
}
