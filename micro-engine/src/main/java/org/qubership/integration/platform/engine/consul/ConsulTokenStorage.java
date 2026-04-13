package org.qubership.integration.platform.engine.consul;

import com.netcracker.cloud.consul.provider.common.TokenStorage;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
@IfBuildProperty(name = "quarkus.consul-source-config.m2m.enabled", stringValue = "false")
public class ConsulTokenStorage implements TokenStorage {
    @ConfigProperty(name = "consul.token")
    Optional<String> token;

    @Override
    public String get() {
        return token.orElse("");
    }

    @Override
    public void update(String token) {
        // Do nothing
    }
}
