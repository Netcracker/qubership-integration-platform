package org.qubership.integration.platform.engine.consul;


import org.springframework.stereotype.Component;

@Component
public class ConsulKeyValidator {
    String makeKeyValid(final String key) {
        return key.replaceAll("[^a-zA-Z0-9\\-~]", "_");
    }
}
