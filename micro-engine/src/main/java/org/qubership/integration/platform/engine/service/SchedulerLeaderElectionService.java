package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Identifier;
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
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.qubership.integration.platform.engine.consul.ConsulKeyValidator;
import org.qubership.integration.platform.engine.consul.ConsulSessionService;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 *
 * <p>The key keeps when its leader was last confirmed and which triggers it ran, also after the leader stops or
 * its session expires, so the next leader fires once what fell into the gap.
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

    record LeaderRecord(String host, long seenAt, List<TriggerName> triggers) {
    }

    record TriggerName(String group, String name) {
    }

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

    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    Clock clock = Clock.systemUTC();

    private String sessionId;
    private boolean leader;
    private Instant confirmedAt;
    private boolean consulFailing;
    // The scheduled election keeps running during shutdown and would take the released key again.
    private boolean stopped;

    @Scheduled(every = ELECTION_INTERVAL, executeWith = Scheduled.SIMPLE,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void elect() {
        if (stopped) {
            return;
        }
        Instant attemptedAt = clock.instant();
        try {
            createOrRenewSession();
            if (leader) {
                confirmLeadership(attemptedAt);
            } else {
                tryToLead(attemptedAt);
            }
            consulFailing = false;
        } catch (Exception e) {
            handleElectionFailure(e);
        }
    }

    // Releasing the key, unlike letting the session expire, skips the lock delay, so another pod takes over at once.
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
        stepDown();
        if (nonNull(sessionId)) {
            if (wasLeader) {
                writeKey(clock.instant(), new KeyValueOptions().setReleaseSession(sessionId));
            }
            awaitConsul(consulClientSupplier.get().destroySession(sessionId));
            sessionId = null;
        }
    }

    private void createOrRenewSession() {
        if (isNull(sessionId)) {
            sessionId = createSession();
        } else {
            renewSession();
        }
    }

    private void confirmLeadership(Instant attemptedAt) {
        if (acquireKey(attemptedAt) || heldByThisSession(readKey())) {
            confirmedAt = attemptedAt;
        } else {
            stepDown();
        }
    }

    private void tryToLead(Instant attemptedAt) {
        KeyValue previous = readKey();
        if (!heldByAnotherSession(previous) && acquireKey(attemptedAt)) {
            confirmedAt = attemptedAt;
            lead(parse(previous));
        }
    }

    private void handleElectionFailure(Exception e) {
        if (ConsulSessionService.sessionNotFoundError(e)) {
            log.warn("Scheduler leader session {} is gone, will create a new one", sessionId);
            sessionId = null;
            stepDown();
        } else {
            logConsulFailure(e);
            if (leader && clock.instant().isAfter(confirmedAt.plusSeconds(SESSION_TTL_SECONDS))) {
                stepDown();
            }
        }
    }

    private void lead(LeaderRecord previous) {
        leader = true;
        if (isNull(previous)) {
            quartzSchedulerService.takeLeadership(null, Set.of());
        } else {
            quartzSchedulerService.takeLeadership(Instant.ofEpochMilli(previous.seenAt()), previous.triggers().stream()
                    .map(trigger -> TriggerKey.triggerKey(trigger.name(), trigger.group()))
                    .collect(Collectors.toSet()));
        }
    }

    private void stepDown() {
        if (leader) {
            leader = false;
            quartzSchedulerService.releaseLeadership();
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
                .setBehavior(SessionBehavior.RELEASE);
        return awaitConsul(consulClientSupplier.get().createSessionWithOptions(options));
    }

    private void renewSession() {
        awaitConsul(consulClientSupplier.get().renewSession(sessionId));
    }

    private boolean acquireKey(Instant seenAt) {
        return writeKey(seenAt, new KeyValueOptions().setAcquireSession(sessionId));
    }

    private boolean writeKey(Instant seenAt, KeyValueOptions options) {
        return Boolean.TRUE.equals(awaitConsul(consulClientSupplier.get().putValueWithOptions(key(), leaderRecord(seenAt),
                options)));
    }

    private KeyValue readKey() {
        return awaitConsul(consulClientSupplier.get().getValue(key()));
    }

    // Consul refuses an acquire during a key's lock delay even to the session that holds the key. That happens
    // when the previous holder's session expires just as this pod takes the key.
    private boolean heldByThisSession(KeyValue keyValue) {
        return nonNull(keyValue) && sessionId.equals(keyValue.getSession());
    }

    private boolean heldByAnotherSession(KeyValue keyValue) {
        return nonNull(keyValue) && nonNull(keyValue.getSession()) && !sessionId.equals(keyValue.getSession());
    }

    private String leaderRecord(Instant seenAt) {
        try {
            List<TriggerName> triggers = quartzSchedulerService.getTriggerKeys().stream()
                    .map(key -> new TriggerName(key.getGroup(), key.getName()))
                    .toList();
            return objectMapper.writeValueAsString(new LeaderRecord(engineInfo.getHost(), seenAt.toEpochMilli(),
                    triggers));
        } catch (JsonProcessingException | SchedulerException e) {
            throw new IllegalStateException("Failed to describe the scheduler for its leader key", e);
        }
    }

    // A key written before the record existed, or by hand, holds no record.
    private LeaderRecord parse(KeyValue keyValue) {
        if (isNull(keyValue) || isNull(keyValue.getValue())) {
            return null;
        }
        try {
            return objectMapper.readValue(keyValue.getValue(), LeaderRecord.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String key() {
        return keyPrefix + keyEngineConfigRoot + keySchedulerLeaders + "/"
                + consulKeyValidator.makeKeyValid(engineInfo.getDomain() + "-" + bgVersion);
    }

    private <T> T awaitConsul(Uni<T> uni) {
        return uni.await().atMost(CONSUL_TIMEOUT);
    }
}
