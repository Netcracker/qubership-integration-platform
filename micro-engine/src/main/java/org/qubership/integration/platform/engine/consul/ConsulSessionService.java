package org.qubership.integration.platform.engine.consul;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.SessionBehavior;
import io.vertx.ext.consul.SessionOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@ApplicationScoped
public class ConsulSessionService {
    public static final String CREATE_SESSION_EVENT = "consul-session-created";

    private static final String SESSION_RENEW_INTERVAL = "30s";
    private static final String SESSION_PREFIX = "qip-engine-session-";
    private static final SessionBehavior SESSION_BEHAVIOR = SessionBehavior.DELETE;
    private static final long SESSION_TTL = 60;
    private static final long CONSUL_AWAIT_BUFFER_MS = 1_000;

    @ConfigProperty(name = "consul.connectTimeout")
    int consulConnectTimeout;

    @ConfigProperty(name = "consul.timeout")
    int consulTimeout;

    @Inject
    Supplier<ConsulClient> consulClientSupplier;

    @Inject
    EventBus eventBus;

    private String sessionId;
    private String previousSessionId;

    public synchronized String getOrCreateSession() {
        if (isNull(sessionId)) {
            doCreateOrRenewSession();
        }
        return sessionId;
    }

    public Duration getAwaitTimeout() {
        return consulAwaitTimeout();
    }

    @Scheduled(
            every = SESSION_RENEW_INTERVAL,
            executeWith = Scheduled.SIMPLE
    )
    public void createOrRenewSession() {
        doCreateOrRenewSession();
    }

    private synchronized void doCreateOrRenewSession() {
        try {
            if (isNull(sessionId)) {
                if (nonNull(previousSessionId) && !deletePreviousSession()) {
                    return;
                }
                log.debug("Create consul session");
                sessionId = createSession();
                log.debug("Consul session created: {}", sessionId);
                notifySessionCreated();
            } else {
                log.debug("Renew consul session");
                renewSession(sessionId);
            }
        } catch (InvalidConsulSessionException e) {
            log.warn("Consul session expired, scheduling destroy and creating a new one: {}", sessionId);
            previousSessionId = sessionId;
            sessionId = null;
        } catch (ConsulOperationTimeoutException e) {
            if (nonNull(sessionId)) {
                log.warn("Consul session renew timed out, will retry with the same session id: {}", sessionId);
            } else {
                log.error("Consul session creation timed out. Consul agent might be unreachable.", e);
            }
        } catch (Exception e) {
            if (nonNull(sessionId)) {
                log.warn("Failed to renew consul session due to unexpected error, will retry with the same session id", e);
            } else {
                log.error("Failed to create consul session due to unexpected error", e);
            }
        }
    }

    private boolean deletePreviousSession() {
        if (isNull(previousSessionId)) {
            return true;
        }

        try {
            log.info("Delete old consul session: {}", previousSessionId);
            deleteSession(previousSessionId);
            previousSessionId = null;
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete previous consul session {}, will retry", previousSessionId, e);
            return false;
        }
    }

    private void notifySessionCreated() {
        eventBus.publish(CREATE_SESSION_EVENT, sessionId);
    }

    private void renewSession(String id) {
        awaitConsul(consulClientSupplier.get().renewSession(id)
            .onFailure().invoke(failure -> {
                if (!sessionNotFoundError(failure)) {
                    log.error("Failed to renew session in consul: {}", failure.getMessage());
                }
            })
            .onFailure(this::sessionNotFoundError).transform(failure -> new InvalidConsulSessionException(id, failure))
        );
    }

    private String createSession() {
        String name = SESSION_PREFIX + UUID.randomUUID();
        SessionOptions options = new SessionOptions()
            .setName(name)
            .setTtl(SESSION_TTL)
            .setBehavior(SESSION_BEHAVIOR);
        return awaitConsul(consulClientSupplier.get().createSessionWithOptions(options)
            .onFailure().invoke(failure -> log.error("Failed to create session in consul: {}", failure.getMessage()))
        );
    }

    private void deleteSession(String id) {
        awaitConsul(consulClientSupplier.get().destroySession(id)
                .onFailure()
                .recoverWithUni(failure -> {
                    if (sessionNotFoundError(failure)) {
                        log.debug("Consul session already gone: {}", id);
                        return Uni.createFrom().voidItem();
                    }
                    log.error("Failed to delete session from consul: {}", failure.getMessage());
                    return Uni.createFrom().failure(failure);
                }));
    }

    private <T> T awaitConsul(Uni<T> uni) {
        try {
            return uni.await().atMost(consulAwaitTimeout());
        } catch (TimeoutException e) {
            throw new ConsulOperationTimeoutException(
                "Consul operation exceeded safety threshold of " + consulAwaitTimeout().toMillis() + "ms", e
            );
        }
    }

    private Duration consulAwaitTimeout() {
        return Duration.ofMillis((long) consulConnectTimeout + consulTimeout + CONSUL_AWAIT_BUFFER_MS);
    }

    private boolean sessionNotFoundError(Throwable e) {
        return e.getMessage() != null && e.getMessage().matches("Session id .* not found");
    }
}
