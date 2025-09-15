package org.qubership.integration.platform.engine.consul;

import io.quarkus.scheduler.Scheduled;
import io.vertx.ext.consul.SessionBehavior;
import io.vertx.ext.consul.SessionOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;

import java.util.UUID;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@ApplicationScoped
public class ConsulSessionService {
    public static final String CREATE_SESSION_EVENT = "consul-session-created";

    private static final String SESSION_RENEW_INTERVAL = "30";
    private static final String SESSION_PREFIX = "qip-engine-session-";
    private static final SessionBehavior SESSION_BEHAVIOR = SessionBehavior.DELETE;
    private static final long SESSION_TTL = 60;

    @Inject
    ConsulClient consulClient;

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

    @Scheduled(every = SESSION_RENEW_INTERVAL)
    public void createOrRenewSession() {
        doCreateOrRenewSession();
    }

    private synchronized void doCreateOrRenewSession() {
        try {
            if (isNull(sessionId)) {
                if (nonNull(previousSessionId)) {
                    log.info("Delete old consul session: {}", previousSessionId);
                    deleteSession(previousSessionId);
                    previousSessionId = null;
                }
                log.debug("Create consul session");
                sessionId = createSession();
                log.debug("Consul session created: {}", sessionId);
                notifySessionCreated();
            } else {
                log.debug("Renew consul session");
                renewSession(sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to create/renew consul session", e);
            previousSessionId = sessionId;
            sessionId = null;
        }
    }

    private void notifySessionCreated() {
        eventBus.publish(CREATE_SESSION_EVENT, sessionId);
    }

    private void renewSession(String id) {
        consulClient.renewSession(id)
                .onFailure()
                .transform(failure -> {
                    log.error("Failed to renew session in consul: {}", failure.getMessage());
                    return failure;
                })
                .await()
                .indefinitely();
    }

    private String createSession() {
        String name = SESSION_PREFIX + UUID.randomUUID();
        SessionOptions options = new SessionOptions()
                .setName(name)
                .setTtl(SESSION_TTL)
                .setBehavior(SESSION_BEHAVIOR);
        return consulClient.createSessionWithOptions(options)
                .onFailure()
                .transform(failure -> {
                    log.error("Failed to create session in consul: {}", failure.getMessage());
                    return failure;
                })
                .await()
                .indefinitely();
    }

    private void deleteSession(String id) {
        consulClient.destroySession(id).onFailure().transform(failure -> {
            log.error("Failed to delete session from consul: {}", failure.getMessage());
            return failure;
        }).await().indefinitely();
    }
}
