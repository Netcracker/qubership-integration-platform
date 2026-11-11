package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DispatchLeaseHeartbeatTest {

  private DispatchLeaseHeartbeat heartbeat;

  @AfterEach
  void tearDown() {
    if (heartbeat != null) {
      heartbeat.shutdown();
    }
  }

  @Test
  void validatesHeartbeatStrictlyBelowLease() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new DispatchLeaseHeartbeat(
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor()));
  }

  @Test
  void tickRenewsOwnershipAndStopsOnLoss() throws Exception {
    heartbeat =
        new DispatchLeaseHeartbeat(
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
    AtomicInteger renewals = new AtomicInteger();
    AtomicBoolean lost = new AtomicBoolean();
    CountDownLatch lostLatch = new CountDownLatch(1);
    UUID owner = UUID.randomUUID();
    AutoCloseable handle =
        heartbeat.start(
            owner,
            () -> {
              int n = renewals.incrementAndGet();
              return n < 3;
            },
            () -> {
              lost.set(true);
              lostLatch.countDown();
            });
    heartbeat.tickAllSynchronouslyForTest();
    heartbeat.tickAllSynchronouslyForTest();
    heartbeat.tickAllSynchronouslyForTest();
    assertTrue(lostLatch.await(2, TimeUnit.SECONDS));
    assertTrue(lost.get());
    assertEquals(0, heartbeat.activeCountForTest());
    closeQuietly(handle);
  }

  @Test
  void closeReleasesHeartbeatResources() throws Exception {
    heartbeat =
        new DispatchLeaseHeartbeat(
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
    AutoCloseable handle =
        heartbeat.start(UUID.randomUUID(), () -> true, () -> {});
    assertEquals(1, heartbeat.activeCountForTest());
    handle.close();
    assertEquals(0, heartbeat.activeCountForTest());
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }
}
