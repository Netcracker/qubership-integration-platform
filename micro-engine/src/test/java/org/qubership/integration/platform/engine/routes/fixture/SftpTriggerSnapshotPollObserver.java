package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.PollingConsumerPollStrategy;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultPollingConsumerPollStrategy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpTriggerSnapshotPollObserver implements PollingConsumerPollStrategy {
    private final PollingConsumerPollStrategy delegate;
    private final Semaphore polling = new Semaphore(1, true);
    private final AtomicLong completed = new AtomicLong();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<Exchange> failureExchanges = new CopyOnWriteArrayList<>();
    private Consumer observed;
    private boolean paused;
    private volatile CountDownLatch followingPolls;

    SftpTriggerSnapshotPollObserver(PollingConsumerPollStrategy delegate) {
        this.delegate = delegate == null ? new DefaultPollingConsumerPollStrategy() : delegate;
    }

    @Override
    public boolean begin(Consumer consumer, Endpoint endpoint) {
        CountDownLatch held = followingPolls;
        if (held != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                while (!held.await(25, TimeUnit.MILLISECONDS)) {
                    if (consumer instanceof DefaultConsumer nativeConsumer
                            && (nativeConsumer.isSuspendingOrSuspended() || nativeConsumer.isStoppingOrStopped())) {
                        return false;
                    }
                    assertTrue(System.nanoTime() < deadline, "SFTP completion-count poll gate was not released.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SFTP completion-count poll gate was interrupted.", exception);
            }
        }
        try {
            assertTrue(polling.tryAcquire(25, TimeUnit.SECONDS), "SFTP native poll gate was not released.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SFTP native poll was interrupted.", exception);
        }
        if (observed != consumer && consumer instanceof DefaultConsumer nativeConsumer) {
            observed = consumer;
            ExceptionHandler original = nativeConsumer.getExceptionHandler();
            nativeConsumer.setExceptionHandler(new ExceptionHandler() {
                @Override
                public void handleException(Throwable exception) {
                    failures.add(exception);
                    original.handleException(exception);
                }

                @Override
                public void handleException(String message, Throwable exception) {
                    failures.add(exception);
                    original.handleException(message, exception);
                }

                @Override
                public void handleException(String message, Exchange exchange, Throwable exception) {
                    failures.add(exception);
                    failureExchanges.add(exchange.copy());
                    original.handleException(message, exchange, exception);
                }
            });
        }
        boolean begin = delegate.begin(consumer, endpoint);
        if (!begin) {
            polling.release();
        }
        return begin;
    }

    @Override
    public void commit(Consumer consumer, Endpoint endpoint, int messages) {
        try {
            delegate.commit(consumer, endpoint, messages);
        } finally {
            completed.incrementAndGet();
            polling.release();
        }
    }

    @Override
    public boolean rollback(Consumer consumer, Endpoint endpoint, int retryCounter, Exception exception) throws Exception {
        failures.add(exception);
        try {
            return delegate.rollback(consumer, endpoint, retryCounter, exception);
        } finally {
            completed.incrementAndGet();
            polling.release();
        }
    }

    long completed() {
        return completed.get();
    }

    List<Throwable> failures() {
        return List.copyOf(failures);
    }

    void clearFailures() {
        failures.clear();
        failureExchanges.clear();
    }

    List<Exchange> failureExchanges() {
        return List.copyOf(failureExchanges);
    }

    void pause() throws InterruptedException {
        if (!paused) {
            assertTrue(polling.tryAcquire(20, TimeUnit.SECONDS), "SFTP native poll did not become idle.");
            paused = true;
        }
    }

    void resume() {
        if (paused) {
            paused = false;
            polling.release();
        }
    }

    void holdFollowingPolls() {
        followingPolls = new CountDownLatch(1);
    }

    void releaseFollowingPolls() {
        if (followingPolls != null) {
            followingPolls.countDown();
            followingPolls = null;
        }
    }
}
