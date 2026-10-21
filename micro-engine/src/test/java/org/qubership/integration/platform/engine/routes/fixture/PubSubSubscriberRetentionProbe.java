package org.qubership.integration.platform.engine.routes.fixture;

import com.google.api.core.ApiService;
import com.google.cloud.pubsub.v1.Subscriber;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PubSubSubscriberRetentionProbe {
    private final AtomicLong created = new AtomicLong();
    private final ReferenceQueue<Subscriber> collected = new ReferenceQueue<>();
    private final ConcurrentHashMap<WeakReference<Subscriber>, Boolean> subscribers = new ConcurrentHashMap<>();

    public void track(Subscriber subscriber) {
        purgeCollected();
        subscribers.put(new WeakReference<>(subscriber, collected), Boolean.TRUE);
        created.incrementAndGet();
    }

    public long createdCount() {
        return created.get();
    }

    public Map<ApiService.State, Long> liveSubscriberStates() {
        Map<ApiService.State, Long> states = new EnumMap<>(ApiService.State.class);
        subscribers.keySet().forEach(reference -> {
            Subscriber subscriber = reference.get();
            if (subscriber != null) {
                states.merge(subscriber.state(), 1L, Long::sum);
            }
        });
        return Map.copyOf(states);
    }

    public Snapshot sampleAfterGc() {
        long before = collections();
        System.gc();
        assertTrue(collections() > before, "Pub/Sub retention sampling requires an observed garbage collection.");
        purgeCollected();
        long live = subscribers.keySet().stream().filter(reference -> reference.get() != null).count();
        return new Snapshot(created.get(), live,
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                ManagementFactory.getThreadMXBean().getThreadCount());
    }

    private static long collections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(collector -> Math.max(0, collector.getCollectionCount())).sum();
    }

    private void purgeCollected() {
        WeakReference<?> reference;
        while ((reference = (WeakReference<?>) collected.poll()) != null) {
            subscribers.remove(reference);
        }
    }

    public record Snapshot(long createdCount, long liveSubscribers, long heapUsedBytes, int threadCount) {
    }
}
