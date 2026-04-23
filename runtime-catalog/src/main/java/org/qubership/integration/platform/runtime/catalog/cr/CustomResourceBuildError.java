package org.qubership.integration.platform.runtime.catalog.cr;

public class CustomResourceBuildError extends RuntimeException {
    public CustomResourceBuildError(String message) {
        super(message);
    }

    public CustomResourceBuildError(String message, Throwable cause) {
        super(message, cause);
    }
}
