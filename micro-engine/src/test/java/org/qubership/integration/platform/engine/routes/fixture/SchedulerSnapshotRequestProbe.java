package org.qubership.integration.platform.engine.routes.fixture;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RequestScoped
public class SchedulerSnapshotRequestProbe {
    private final Lifetime lifetime = new Lifetime(UUID.randomUUID().toString(), new CompletableFuture<>());

    public Lifetime lifetime() {
        return lifetime;
    }

    @PreDestroy
    public void destroy() {
        lifetime.destroyed().complete(null);
    }

    public record Lifetime(String id, CompletableFuture<Void> destroyed) {
    }
}
