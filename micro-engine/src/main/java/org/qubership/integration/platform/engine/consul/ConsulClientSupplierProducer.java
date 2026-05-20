package org.qubership.integration.platform.engine.consul;

import com.netcracker.cloud.consul.provider.common.TokenStorage;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.function.Supplier;

@ApplicationScoped
public class ConsulClientSupplierProducer {
    @ConfigProperty(name = "consul.url")
    URI uri;

    @ConfigProperty(name = "consul.connectTimeout")
    Integer connectTimeout;

    @ConfigProperty(name = "consul.timeout")
    Integer timeout;

    @Produces
    public Supplier<ConsulClient> consulClient(
            Vertx vertx,
            TokenStorage tokenStorage
    ) {
        return () -> {
            ConsulClientOptions options = new ConsulClientOptions(uri)
                    .setAclToken(tokenStorage.get())
                    .setConnectTimeout(connectTimeout)
                    .setTimeout(timeout);
            return ConsulClient.create(vertx, options);
        };
    }
}
