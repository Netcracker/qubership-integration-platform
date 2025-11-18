package org.qubership.integration.platform.engine.consul;

import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

@ApplicationScoped
public class ConsulRestClientProducer {
    @ConfigProperty(name = "consul.url")
    URI uri;

    @ConfigProperty(name = "consul.token")
    String token;

    @Produces
    public ConsulClient consulClient(Vertx vertx) {
        ConsulClientOptions options = new ConsulClientOptions(uri)
                .setAclToken(token);
        return ConsulClient.create(vertx, options);
    }
}
