package org.qubership.integration.platform.engine.maas.kafka;

import lombok.Getter;

public enum AuthType {
    PLAIN("plain"),
    SSL_CERT("sslCert"),
    SCRAM("SCRAM"),
    SSL_CERT_PLUS_PLAIN("sslCert+plain"),
    SSL_CERT_PLUS_SCRAM("sslCert+SCRAM");

    @Getter
    private final String name;

    AuthType(String name) {
        this.name = name;
    }
}
