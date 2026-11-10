package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.util.ExchangeUtil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class HttpDeadlineSnapshotProbe {
    static final String BEAN_NAME = "snapshotHttpDeadlineProbe";
    private final AtomicInteger firstCalls = new AtomicInteger();
    private final AtomicInteger followingCalls = new AtomicInteger();
    private volatile CountDownLatch release = new CountDownLatch(1);
    private volatile Exchange exchange;
    private volatile long processorFinishedAt;

    void reset() {
        firstCalls.set(0);
        followingCalls.set(0);
        exchange = null;
        processorFinishedAt = 0;
        release = new CountDownLatch(1);
    }

    public void processFirst(Exchange incoming) throws InterruptedException {
        assertEquals(1, firstCalls.incrementAndGet(), "The first processor must run once per HTTP request.");
        exchange = incoming;
        String sessionId = ExchangeUtil.getSessionId(incoming);
        Long started = incoming.getProperty(Properties.START_TIME_MS, Long.class);
        Long timeout = incoming.getProperty(Properties.CHAIN_TIME_OUT_AFTER, Long.class);
        assertNotNull(sessionId, "The debugger must create the session before the first processor.");
        assertNotNull(started, "The debugger must initialize the session start time.");
        assertEquals(Long.valueOf(5000), timeout, "The generated HTTP trigger must configure its session timeout.");
        if (Boolean.parseBoolean(incoming.getMessage().getHeader("X-Snapshot-Cross-Deadline", String.class))) {
            long remaining = Math.max(1, started + timeout - System.currentTimeMillis() + 100);
            assertTrue(remaining <= 5100, "The deadline must use the current request's session start time.");
            assertFalse(release.await(remaining, TimeUnit.MILLISECONDS), "The deadline probe was released before completion.");
        }
        processorFinishedAt = System.currentTimeMillis();
        assertFalse(incoming.getProperty(Properties.CHAIN_TIMED_OUT, false, Boolean.class),
                "The debugger checks the deadline after the processor returns.");
        assertNull(incoming.getException(), "The slow processor must return without an injected failure.");
        incoming.getMessage().setBody("First processor completed");
    }

    public void processFollowing(Exchange incoming) {
        assertEquals(1, followingCalls.incrementAndGet(), "The following processor must run once after recovery.");
        incoming.getMessage().setBody("Deadline recovered");
    }

    Exchange exchange() {
        return exchange;
    }

    long processorFinishedAt() {
        return processorFinishedAt;
    }

    int firstCalls() {
        return firstCalls.get();
    }

    int followingCalls() {
        return followingCalls.get();
    }

    void release() {
        release.countDown();
    }
}
