package org.qubership.integration.platform.runtime.catalog.cr;

public class CustomResourceDeployError extends RuntimeException {
    public CustomResourceDeployError(String message) {
        super(message);
    }

    public CustomResourceDeployError(String message, Throwable cause) {
        super(message, cause);
    }
}
