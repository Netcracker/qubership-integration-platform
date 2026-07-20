package org.qubership.integration.platform.engine.cloudcore.maas;

import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;

public class MaasException extends DeploymentRetriableException {
    public MaasException() {
        super();
    }

    public MaasException(String message) {
        super(message);
    }

    public MaasException(String message, Throwable cause) {
        super(message, cause);
    }
}
