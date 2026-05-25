package org.qubership.integration.platform.engine.cloudcore.maas;

public class TopicNotFoundException extends MaasException {

    public TopicNotFoundException() {
        super();
    }

    public TopicNotFoundException(String message) {
        super(message);
    }

    public TopicNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
