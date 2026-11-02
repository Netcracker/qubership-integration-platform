package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaTriggerSnapshotProcessingGate implements Processor, AutoCloseable {
    private final AtomicReference<Gate> pending = new AtomicReference<>();
    private Gate active;

    Gate blockNext() {
        active = new Gate();
        pending.set(active);
        return active;
    }

    @Override
    public void process(Exchange exchange) throws InterruptedException {
        Gate gate = pending.getAndSet(null);
        if (gate != null) {
            gate.started.countDown();
            assertTrue(gate.release.await(25, TimeUnit.SECONDS), "Kafka processing gate was not released within 25 seconds.");
        }
    }

    @Override
    public void close() {
        if (active != null) {
            active.open();
        }
        pending.set(null);
    }

    static final class Gate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(15, TimeUnit.SECONDS), "Kafka record did not reach the processing gate within 15 seconds.");
        }

        void open() {
            release.countDown();
        }
    }
}
