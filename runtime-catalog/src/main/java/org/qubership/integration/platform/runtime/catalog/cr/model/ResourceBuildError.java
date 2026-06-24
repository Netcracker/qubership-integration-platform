package org.qubership.integration.platform.runtime.catalog.cr.model;

public class ResourceBuildError extends RuntimeException {
    public ResourceBuildError(String message) {
        super(message);
    }

    public ResourceBuildError(String message, Throwable cause) {
        super(message, cause);
    }
}
