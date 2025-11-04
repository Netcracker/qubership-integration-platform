package org.qubership.integration.platform.engine.model.maas.kafka;

public enum AuthType {
    PLAIN("plain"),
    SSL_CERT("sslCert"),
    SCRAM("SCRAM"),
    SSL_CERT_PLUS_PLAIN("sslCert+plain"),
    SSL_CERT_PLUS_SCRAM("sslCert+SCRAM");

    private String name;

    AuthType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
