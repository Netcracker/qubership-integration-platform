package org.qubership.integration.platform.engine.errorhandling;

public class ContextStorageException extends RuntimeException {
    public ContextStorageException(String errorMessage, Throwable cause) {
        super(errorMessage, cause);
    }
}
