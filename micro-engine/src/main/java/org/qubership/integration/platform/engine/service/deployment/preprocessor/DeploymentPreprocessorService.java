package org.qubership.integration.platform.engine.service.deployment.preprocessor;

import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

import java.util.List;

@ApplicationScoped
public class DeploymentPreprocessorService {
    private final List<DeploymentPreprocessor> preprocessors;

    @Inject
    public DeploymentPreprocessorService(@All List<DeploymentPreprocessor> preprocessors) {
        this.preprocessors = preprocessors;
    }

    public DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception {
        DeploymentUpdate result = deploymentUpdate;
        for (DeploymentPreprocessor deploymentPreprocessor : preprocessors) {
            result = deploymentPreprocessor.preprocess(result);
        }
        return result;
    }
}
