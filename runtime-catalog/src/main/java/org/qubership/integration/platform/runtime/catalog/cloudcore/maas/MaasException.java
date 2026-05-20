package org.qubership.integration.platform.runtime.catalog.cloudcore.maas;

public class MaasException extends RuntimeException {

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
