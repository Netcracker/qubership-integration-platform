package org.qubership.integration.platform.engine.service.deployment.preprocessor;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

public interface DeploymentPreprocessor {
    DeploymentUpdate preprocess(DeploymentUpdate deploymentUpdate) throws Exception;
}
