package org.qubership.integration.platform.ai.config.consul;

import com.netcracker.cloud.consul.provider.common.TokenStorage;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Supplies the Consul ACL token for {@code consul-config-source}. Without this bean the extension
 * falls back to {@code noopTokenStorage} (empty token) and platform KV under {@code config/} is
 * never loaded into MicroProfile Config.
 */
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

  }
}
