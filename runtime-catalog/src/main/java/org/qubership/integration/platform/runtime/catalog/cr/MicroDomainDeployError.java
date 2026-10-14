package org.qubership.integration.platform.runtime.catalog.cr;

public class MicroDomainDeployError extends RuntimeException {
    public MicroDomainDeployError(String message) {
        super(message);
    }

    public MicroDomainDeployError(String message, Throwable cause) {
        super(message, cause);
    }
}
