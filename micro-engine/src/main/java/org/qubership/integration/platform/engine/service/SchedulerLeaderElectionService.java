package org.qubership.integration.platform.engine.service;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.KeyValue;
import io.vertx.ext.consul.KeyValueOptions;
import io.vertx.ext.consul.SessionBehavior;
import io.vertx.ext.consul.SessionOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.consul.ConsulKeyValidator;
import org.qubership.integration.platform.engine.consul.ConsulSessionService;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Elects the one pod of a micro domain and blue-green version that runs its scheduler. Every pod keeps the jobs
 * of its own chain sources in memory, so the pods of a previous deployment keep their schedule until they stop.
 *
 * <p>The leader holds a Consul key under a session of its own. Consul hands the key to another pod no earlier
 * than the TTL plus the lock delay after the leader's last renewal. A leader that cannot confirm the key steps
 * down a TTL after its last confirmed renewal, plus at most one election interval and three Consul calls, which
 * is still inside that window.
 */
@Slf4j
@ApplicationScoped
public class SchedulerLeaderElectionService {
    private static final String ELECTION_INTERVAL = "3s";
    private static final String SESSION_NAME_PREFIX = "cip-engine-scheduler-leader-";
    // The shortest TTL Consul accepts.
    private static final long SESSION_TTL_SECONDS = 10;
    private static final long LOCK_DELAY_SECONDS = 15;
    private static final Duration CONSUL_TIMEOUT = Duration.ofSeconds(3);
    private static final long RELEASE_TIMEOUT_SECONDS = 2;

    @ConfigProperty(name = "consul.keys.prefix")
    String keyPrefix;

    @ConfigProperty(name = "consul.keys.engine-config-root")
    String keyEngineConfigRoot;

    @ConfigProperty(name = "consul.keys.scheduler-leaders")
    String keySchedulerLeaders;

    @ConfigProperty(name = "cloud.microservice.bg_version")
    String bgVersion;

    @Inject
    Supplier<ConsulClient> consulClientSupplier;

    @Inject
    ConsulKeyValidator consulKeyValidator;

    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Inject
    EngineInfo engineInfo;

    Clock clock = Clock.systemUTC();

    private String sessionId;
    private boolean leader;
    private Instant confirmedAt;
    private boolean consulFailing;
    // The scheduled election keeps running during shutdown and would take the deleted key again.
    private boolean stopped;

    @Scheduled(every = ELECTION_INTERVAL, executeWith = Scheduled.SIMPLE,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void elect() {
        if (stopped) {
            return;
        }
        Instant attemptedAt = clock.instant();
        try {
            if (isNull(sessionId)) {
                sessionId = createSession();
            } else {
                renewSession();
            }
            boolean acquired = acquireKey();
            if (acquired) {
                confirmedAt = attemptedAt;
            }
            consulFailing = false;
            setLeader(acquired);
        } catch (Exception e) {
            if (ConsulSessionService.sessionNotFoundError(e)) {
                log.warn("Scheduler leader session {} is gone, will create a new one", sessionId);
                sessionId = null;
                setLeader(false);
            } else {
                logConsulFailure(e);
                if (leader && clock.instant().isAfter(confirmedAt.plusSeconds(SESSION_TTL_SECONDS))) {
                    setLeader(false);
                }
            }
        }
    }

    // Deleting the key before destroying the session skips the lock delay, so another pod takes over at once.
    void releaseOnShutdown(@Observes ShutdownEvent event) {
        try {
            CompletableFuture.runAsync(this::release)
                    .orTimeout(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Failed to release scheduler leadership on shutdown", e);
        }
    }

    private synchronized void release() {
        stopped = true;
        boolean wasLeader = leader;
        setLeader(false);
        if (nonNull(sessionId)) {
            ConsulClient client = consulClientSupplier.get();
            if (wasLeader) {
                awaitConsul(client.deleteValue(key()));
            }
            awaitConsul(client.destroySession(sessionId));
            sessionId = null;
        }
    }

    private void setLeader(boolean leader) {
        if (leader != this.leader) {
            this.leader = leader;
            if (leader) {
                quartzSchedulerService.takeLeadership();
            } else {
                quartzSchedulerService.releaseLeadership();
            }
        }
    }

    // An outage fails every election, so only its first failure is a warning.
    private void logConsulFailure(Exception e) {
        if (consulFailing) {
            log.debug("Failed to confirm scheduler leadership in Consul: {}", e.toString());
        } else {
            consulFailing = true;
            log.warn("Failed to confirm scheduler leadership in Consul: {}", e.toString());
        }
    }

    private String createSession() {
        SessionOptions options = new SessionOptions()
                .setName(SESSION_NAME_PREFIX + engineInfo.getHost())
                .setTtl(SESSION_TTL_SECONDS)
                .setLockDelay(LOCK_DELAY_SECONDS)
                .setBehavior(SessionBehavior.DELETE);
        return awaitConsul(consulClientSupplier.get().createSessionWithOptions(options));
    }

    private void renewSession() {
        awaitConsul(consulClientSupplier.get().renewSession(sessionId));
    }

    // Consul refuses an acquire during a key's lock delay even to the session that holds the key. That happens
    // when the previous holder's session expires just as this pod takes the key.
    private boolean acquireKey() {
        ConsulClient client = consulClientSupplier.get();
        boolean acquired = Boolean.TRUE.equals(awaitConsul(client.putValueWithOptions(key(), engineInfo.getHost(),
                new KeyValueOptions().setAcquireSession(sessionId))));
        if (acquired) {
            return true;
        }
        KeyValue holder = awaitConsul(client.getValue(key()));
        return nonNull(holder) && sessionId.equals(holder.getSession());
    }

    private String key() {
        return keyPrefix + keyEngineConfigRoot + keySchedulerLeaders + "/"
                + consulKeyValidator.makeKeyValid(engineInfo.getDomain() + "-" + bgVersion);
    }

    private <T> T awaitConsul(Uni<T> uni) {
        return uni.await().atMost(CONSUL_TIMEOUT);
    }
}
