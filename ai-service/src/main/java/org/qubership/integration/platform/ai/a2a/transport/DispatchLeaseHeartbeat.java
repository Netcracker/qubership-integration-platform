package org.qubership.integration.platform.ai.a2a.transport;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import io.quarkus.runtime.StartupEvent;

/**
 * Shared heartbeat that renews exclusive dispatch leases independently of facade event frequency.
 *
 * <p>Scheduling uses one daemon scheduler; each renewal runs on a dedicated worker pool so one
 * blocked JDBC renew cannot starve unrelated dispatches.
 */
@ApplicationScoped
public class DispatchLeaseHeartbeat {

  private static final Logger LOG = Logger.getLogger(DispatchLeaseHeartbeat.class);

  private final Duration heartbeatInterval;
  private final Duration dispatchLease;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService renewWorkers;
  private final ConcurrentHashMap<UUID, Registration> active = new ConcurrentHashMap<>();

  @Inject
  public DispatchLeaseHeartbeat(AppConfig appConfig) {
    this(
        appConfig.a2a().dispatchHeartbeatInterval(),
        appConfig.a2a().dispatchLease(),
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "a2a-dispatch-lease-heartbeat");
              thread.setDaemon(true);
              return thread;
            }),
        boundedRenewWorkers(
            appConfig.a2a().dispatchRenewWorkers(), "a2a-dispatch-lease-renew"));
  }

  DispatchLeaseHeartbeat(
      Duration heartbeatInterval, Duration dispatchLease, ScheduledExecutorService scheduler) {
    this(
        heartbeatInterval,
        dispatchLease,
        scheduler,
        boundedRenewWorkers(4, "a2a-dispatch-lease-renew-test"));
  }

  /**
   * Builds the fixed renewal pool. The queue matches the pool size, so a saturated pool rejects
   * instead of growing; {@link Registration#scheduleRenew()} turns a rejection into lost ownership.
   */
  private static ExecutorService boundedRenewWorkers(int workers, String threadName) {
    int size = Math.max(1, workers);
    return new ThreadPoolExecutor(
        size,
        size,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(size),
        r -> {
          Thread thread = new Thread(r, threadName);
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  DispatchLeaseHeartbeat(
      Duration heartbeatInterval,
      Duration dispatchLease,
      ScheduledExecutorService scheduler,
      ExecutorService renewWorkers) {
    this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    this.dispatchLease = Objects.requireNonNull(dispatchLease, "dispatchLease");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.renewWorkers = Objects.requireNonNull(renewWorkers, "renewWorkers");
    validateIntervals(heartbeatInterval, dispatchLease);
  }

  void onStart(@Observes StartupEvent event) {
    validateIntervals(heartbeatInterval, dispatchLease);
    LOG.infof(
        "A2A dispatch lease heartbeat interval=%s lease=%s", heartbeatInterval, dispatchLease);
  }

  /**
   * Starts periodic renewal for {@code ownerToken}. {@code renew} must return {@code false} when
   * ownership is lost; the registration stops and invokes {@code onOwnershipLost}.
   */
  public AutoCloseable start(
      UUID ownerToken, BooleanSupplier renew, Runnable onOwnershipLost) {
    Objects.requireNonNull(ownerToken, "ownerToken");
    Objects.requireNonNull(renew, "renew");
    Objects.requireNonNull(onOwnershipLost, "onOwnershipLost");
    Registration registration = new Registration(ownerToken, renew, onOwnershipLost);
    Registration previous = active.put(ownerToken, registration);
    if (previous != null) {
      previous.cancel();
    }
    long periodMs = Math.max(1L, heartbeatInterval.toMillis());
    ScheduledFuture<?> future =
        scheduler.scheduleAtFixedRate(
            registration::scheduleRenew, periodMs, periodMs, TimeUnit.MILLISECONDS);
    registration.attach(future);
    return registration;
  }

  /** Test seam: schedule every active heartbeat renew on the worker pool. */
  void tickAllForTest() {
    for (Registration registration : active.values()) {
      registration.scheduleRenew();
    }
  }

  /** Test seam: run renewals synchronously on the caller thread. */
  void tickAllSynchronouslyForTest() {
    for (Registration registration : active.values()) {
      registration.renewNowForTest();
    }
  }

  int activeCountForTest() {
    return active.size();
  }

  @PreDestroy
  void shutdown() {
    for (Registration registration : active.values()) {
      registration.cancel();
    }
    active.clear();
    scheduler.shutdownNow();
    renewWorkers.shutdownNow();
  }

  static void validateIntervals(Duration heartbeat, Duration lease) {
    if (heartbeat == null || heartbeat.isZero() || heartbeat.isNegative()) {
      throw new IllegalStateException(
          "qip.ai.a2a.dispatch-heartbeat-interval must be positive");
    }
    if (lease == null || lease.isZero() || lease.isNegative()) {
      throw new IllegalStateException("qip.ai.a2a.dispatch-lease must be positive");
    }
    if (!heartbeat.minus(lease).isNegative()) {
      throw new IllegalStateException(
          "qip.ai.a2a.dispatch-heartbeat-interval must be strictly less than"
              + " qip.ai.a2a.dispatch-lease");
    }
  }

  private final class Registration implements AutoCloseable {
    private final UUID ownerToken;
    private final BooleanSupplier renew;
    private final Runnable onOwnershipLost;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean renewInFlight = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;

    private Registration(UUID ownerToken, BooleanSupplier renew, Runnable onOwnershipLost) {
      this.ownerToken = ownerToken;
      this.renew = renew;
      this.onOwnershipLost = onOwnershipLost;
    }

    private void attach(ScheduledFuture<?> future) {
      this.future = future;
    }

    private void scheduleRenew() {
      if (stopped.get()) {
        return;
      }
      if (!renewInFlight.compareAndSet(false, true)) {
        // Previous renew still running; skip this tick rather than queue unbounded work.
        return;
      }
      try {
        renewWorkers.execute(
            () -> {
              try {
                renewNow();
              } finally {
                renewInFlight.set(false);
              }
            });
      } catch (RejectedExecutionException rejected) {
        // Saturated pool cannot prove the lease is still held, so fail closed and give the
        // dispatch up rather than let another owner believe this one is alive.
        renewInFlight.set(false);
        LOG.warnf(
            "Dispatch lease renewal rejected for owner=%s; treating ownership as lost", ownerToken);
        stopAndSignalLoss();
      }
    }

    private void renewNowForTest() {
      renewNow();
    }

    private void renewNow() {
      if (stopped.get()) {
        return;
      }
      boolean renewed;
      try {
        renewed = renew.getAsBoolean();
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Dispatch lease heartbeat renew threw for owner=%s", ownerToken);
        stopAndSignalLoss();
        return;
      }
      if (!renewed) {
        stopAndSignalLoss();
      }
    }

    private void stopAndSignalLoss() {
      if (!cancel()) {
        return;
      }
      try {
        onOwnershipLost.run();
      } catch (RuntimeException ex) {
        LOG.debugf(ex, "onOwnershipLost callback failed for owner=%s", ownerToken);
      }
    }

    private boolean cancel() {
      if (!stopped.compareAndSet(false, true)) {
        return false;
      }
      active.remove(ownerToken, this);
      ScheduledFuture<?> scheduled = future;
      if (scheduled != null) {
        scheduled.cancel(false);
      }
      return true;
    }

    @Override
    public void close() {
      cancel();
    }
  }
}
