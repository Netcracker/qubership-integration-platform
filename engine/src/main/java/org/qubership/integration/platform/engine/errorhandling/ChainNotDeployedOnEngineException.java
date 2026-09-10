package org.qubership.integration.platform.engine.errorhandling;

public class ChainNotDeployedOnEngineException extends RuntimeException {
    public ChainNotDeployedOnEngineException(String message) {
        super(message);
    }
}
