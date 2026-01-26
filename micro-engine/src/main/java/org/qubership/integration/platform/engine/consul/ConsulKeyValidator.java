package org.qubership.integration.platform.engine.consul;


import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ConsulKeyValidator {
    String makeKeyValid(final String key) {
        return key.replaceAll("[^a-zA-Z0-9\\-~]", "-");
    }
}
