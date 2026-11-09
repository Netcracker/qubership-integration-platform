package org.qubership.integration.platform.engine.routes.fixture;

import io.quarkus.arc.Arc;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.quartz.QuartzMessage;
import org.apache.camel.support.DefaultExchange;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class SchedulerSnapshotRuntime {
    private final CamelContext context;
    private final Scheduler scheduler;
    private final TriggerKey key;
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final Map<String, Exchange> byFire = new ConcurrentHashMap<>();
    private final List<SchedulerSnapshotRequestProbe.Lifetime> lifetimes = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<NativeOutcome> nativeOutcomes = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Exchange> finished = new LinkedBlockingQueue<>();
    private final Set<String> fireIds = ConcurrentHashMap.newKeySet();
    private final Set<String> exchangeIds = ConcurrentHashMap.newKeySet();
    private final Set<String> requestContextIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> originalFireIds = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private volatile Map<String, Object> controls = Map.of();
    private volatile int expected;
    private volatile int misfires;
    private volatile CountDownLatch entry = new CountDownLatch(1);
    private volatile CountDownLatch prepared = new CountDownLatch(1);
    private volatile long misfireRecoveryThreshold;
    private volatile CountDownLatch release = new CountDownLatch(0);
    private volatile List<Exchange> results;

    SchedulerSnapshotRuntime(CamelContext context, Scheduler scheduler, TriggerKey key) {
        this.context = context;
        this.scheduler = scheduler;
        this.key = key;
    }

    public static String registryName(String nodeId) {
        return "snapshot-scheduler:" + nodeId;
    }

    void prepare(int count, Map<String, Object> properties) {
        assertEquals(0, active.get(), "Previous native Quartz jobs must finish before the next invocation.");
        received.clear();
        byFire.clear();
        lifetimes.clear();
        failures.clear();
        nativeOutcomes.clear();
        finished.clear();
        maximumActive.set(0);
        controls = properties;
        expected = count;
        results = null;
        misfires = 0;
        entry = new CountDownLatch(1);
        prepared = new CountDownLatch(1);
        misfireRecoveryThreshold = 0;
        release = new CountDownLatch(Boolean.TRUE.equals(properties.get("overlap")) ? 2
                : "graceful-stop".equals(properties.get("action")) ? 1 : 0);
    }

    void entered(Exchange exchange) throws Exception {
        try {
            assertTrue(prepared.await(15, TimeUnit.SECONDS), "Scheduler invocation controls did not finish preparing.");
            assertNull(exchange.getMessage().getBody(), "Native Quartz input body");
            QuartzMessage message = assertQuartzMessage(exchange);
            JobExecutionContext job = message.getJobExecutionContext();
            assertTrue(fireIds.add(job.getFireInstanceId()), "Every native Quartz fire must have a distinct ID.");
            assertTrue(exchangeIds.add(exchange.getExchangeId()),
                    "Every native fire must use a distinct Camel exchange across the scenario.");
            originalFireIds.put(exchange.getExchangeId(), job.getFireInstanceId());
            assertEquals(key, job.getTrigger().getKey(), "Generated trigger selected by native Quartz");
            if (misfireRecoveryThreshold != 0) {
                assertTrue(job.getScheduledFireTime().getTime() >= misfireRecoveryThreshold,
                        "Misfire recovery must schedule a current fire instead of replaying the missed cron tick.");
            }
            assertEquals(job.getFireTime(), message.getHeader("fireTime"));
            assertEquals(job.getScheduledFireTime(), message.getHeader("scheduledFireTime"));
            assertEquals(job.getPreviousFireTime(), message.getHeader("previousFireTime"));
            assertEquals(job.getNextFireTime(), message.getHeader("nextFireTime"));
            assertEquals(job.getJobDetail(), message.getHeader("jobDetail"));
            assertEquals(job.getJobInstance(), message.getHeader("jobInstance"));
            assertEquals(job.getMergedJobDataMap(), message.getHeader("mergedJobDataMap"));
            assertEquals(job.getRefireCount(), message.getHeader("refireCount"));
            assertEquals(job.getResult(), message.getHeader("result"));
            assertEquals(job.getJobRunTime(), message.getHeader("jobRunTime"));
            assertEquals(job.getFireTime().getTime(), message.getHeader("CamelMessageTimestamp"));
            assertEquals(key.getName(), message.getHeader("triggerName"));
            assertEquals(key.getGroup(), message.getHeader("triggerGroup"));
            assertEquals(job.getTrigger(), message.getHeader("trigger"));
            assertEquals(job.getScheduler(), message.getHeader("scheduler"));
            assertFalse(message.getHeaders().containsKey("recovering"), "Quartz has no recovering header.");
            assertTrue(Arc.container().requestContext().isActive(), "Native Quartz workers need an active CDI request context.");
            SchedulerSnapshotRequestProbe.Lifetime lifetime = CDI.current().select(SchedulerSnapshotRequestProbe.class).get().lifetime();
            assertTrue(requestContextIds.add(lifetime.id()), "Every native job needs a fresh request context across the scenario.");
            lifetimes.add(lifetime);
            exchange.setProperty("snapshotSchedulerMarker", job.getFireInstanceId());
            exchange.getMessage().setHeader("snapshotSchedulerMarker", job.getFireInstanceId());
            exchange.setProperty("snapshotSchedulerFail", Boolean.TRUE.equals(controls.get("snapshotSchedulerFail")));
            byFire.put(job.getFireInstanceId(), exchange);
            received.add(exchange);
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            assertTrue(received.size() <= expected, "Quartz delivered an unexpected extra firing: " + key);
            if (received.size() == expected && !"graceful-stop".equals(controls.get("action"))) {
                scheduler.pauseTrigger(key);
            }
            entry.countDown();
            if (Boolean.TRUE.equals(controls.get("overlap"))) {
                release.countDown();
            }
            assertTrue(release.await(20, TimeUnit.SECONDS), "Scheduled processing gate did not release.");
        } catch (Exception | AssertionError failure) {
            failures.add(failure);
            entry.countDown();
            throw failure;
        }
    }

    private QuartzMessage assertQuartzMessage(Exchange exchange) {
        assertTrue(exchange.getMessage() instanceof QuartzMessage, "The input must be a native QuartzMessage.");
        return (QuartzMessage) exchange.getMessage();
    }

    void jobFinished(JobExecutionContext job, JobExecutionException failure) {
        Exchange exchange = byFire.get(job.getFireInstanceId());
        if (exchange != null) {
            nativeOutcomes.add(new NativeOutcome(exchange, failure));
            active.decrementAndGet();
            finished.add(exchange);
        }
    }

    void misfired() {
        misfires++;
    }

    void triggerPaused() {
        if ("graceful-stop".equals(controls.get("action")) && !received.isEmpty()) {
            releasePendingWork();
        }
    }

    void awaitEntry() throws Exception {
        assertTrue(entry.await(15, TimeUnit.SECONDS), "Native scheduled job did not enter its route.");
    }

    public Exchange await() throws Exception {
        List<Exchange> completed = awaitResults();
        if (expected == 0) {
            return new DefaultExchange(context);
        }
        return completed.getLast();
    }

    // Later observer failures cannot change the scenario result after the first failure.
    @SuppressWarnings("java:S1751")
    synchronized List<Exchange> awaitResults() throws Exception {
        if (results == null) {
            List<Exchange> completed = new ArrayList<>();
            for (int index = 0; index < expected; index++) {
                Exchange exchange = finished.poll(20, TimeUnit.SECONDS);
                assertNotNull(exchange, "Native Quartz job did not complete. " + diagnostics());
                completed.add(exchange);
            }
            for (Throwable failure : failures) {
                if (failure instanceof AssertionError assertion) {
                    throw assertion;
                }
                throw new IllegalStateException("Native scheduler observation failed.", failure);
            }
            assertEquals(expected, received.size(), "Native scheduler entry count");
            if (Boolean.TRUE.equals(controls.get("overlap"))) {
                assertEquals(2, maximumActive.get(), "Generated stateless jobs must allow overlapping native fires.");
            }
            if (Boolean.TRUE.equals(controls.get("verifyRequestContext"))) {
                for (SchedulerSnapshotRequestProbe.Lifetime lifetime : lifetimes) {
                    lifetime.destroyed().get(5, TimeUnit.SECONDS);
                }
                assertEquals(lifetimes.size(), lifetimes.stream().map(SchedulerSnapshotRequestProbe.Lifetime::id).distinct().count(),
                        "Each Quartz job needs a fresh request context.");
            }
            assertEquals(expected, nativeOutcomes.size(), "One native Quartz outcome per observed exchange");
            for (NativeOutcome outcome : nativeOutcomes) {
                Exception exchangeFailure = outcome.exchange().getException();
                if (exchangeFailure == null) {
                    assertNull(outcome.failure(), "Successful and handled exchanges must finish as successful native jobs.");
                } else {
                    assertNotNull(outcome.failure(), "Unhandled exchange failure must reach the native Quartz job outcome.");
                    assertEquals(JobExecutionException.class, outcome.failure().getClass());
                    assertSame(exchangeFailure, outcome.failure().getCause(), "Native job failure must retain its exchange's exact cause.");
                    assertEquals(exchangeFailure.toString(), outcome.failure().getMessage());
                }
            }
            for (Exchange exchange : completed) {
                String original = originalFireIds.get(exchange.getExchangeId());
                assertEquals(original, exchange.getProperty("snapshotSchedulerMarker"), "Original native fire ID in exchange properties");
                assertEquals(original, exchange.getMessage().getHeader("snapshotSchedulerMarker"), "Original native fire ID in message headers");
            }
            results = List.copyOf(completed);
        }
        return results;
    }

    void assertAbsentThroughNextFire() throws Exception {
        org.quartz.Trigger trigger = scheduler.getTrigger(key);
        if (trigger instanceof CronTrigger cron) {
            Date boundary = cron.getFireTimeAfter(new Date());
            if (boundary != null) {
                long wait = Math.max(1, boundary.getTime() - System.currentTimeMillis() + 100);
                assertNull(finished.poll(wait, TimeUnit.MILLISECONDS), "A paused scheduler must not execute business work.");
            }
        }
        assertTrue(received.isEmpty(), "A stopped or inactive scheduler must not enter its route.");
    }

    void ready() {
        prepared.countDown();
    }

    void expectMisfireAfter(long threshold) {
        misfireRecoveryThreshold = threshold;
    }

    void releasePendingWork() {
        prepared.countDown();
        while (release.getCount() != 0) {
            release.countDown();
        }
    }

    int misfires() {
        return misfires;
    }

    int receivedCount() {
        return received.size();
    }

    private record NativeOutcome(Exchange exchange, JobExecutionException failure) {
    }

    String diagnostics() {
        return "trigger=" + key + ", entries=" + received.size() + ", active=" + active.get()
                + ", completed=" + finished.size() + ", misfires=" + misfires + ", failures=" + failures + ", nativeOutcomes=" + nativeOutcomes;
    }
}
