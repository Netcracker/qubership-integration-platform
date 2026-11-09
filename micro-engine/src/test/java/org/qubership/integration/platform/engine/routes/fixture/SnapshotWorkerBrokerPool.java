package org.qubership.integration.platform.engine.routes.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SnapshotWorkerBrokerPool {
    public static final String ENABLED_PROPERTY = "snapshot.broker.reuse";
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotWorkerBrokerPool.class);
    private static final Object LOCK = new Object();
    private static final Map<String, Long> CREATED = new LinkedHashMap<>();
    private static final Map<String, Long> REUSED = new LinkedHashMap<>();
    private static final Map<String, Long> CLOSED = new LinkedHashMap<>();
    private static ScenarioScope current;
    private static Entry cached;

    private SnapshotWorkerBrokerPool() {
    }

    public interface Resource extends AutoCloseable {
        void resetAfterScenario() throws Exception;

        @Override
        void close() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static ScenarioScope beginScenario(boolean requested, String name) throws Exception {
        Entry obsolete = null;
        ScenarioScope scope;
        synchronized (LOCK) {
            if (current != null) {
                throw new IllegalStateException("A snapshot broker scenario is already active.");
            }
            boolean enabled = requested && Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
            scope = new ScenarioScope(name, enabled);
            current = scope;
            if (!enabled) {
                obsolete = cached;
                cached = null;
            }
        }
        try {
            destroy(obsolete);
            return scope;
        } catch (Exception | Error failure) {
            synchronized (LOCK) {
                current = null;
            }
            throw failure;
        }
    }

    public static boolean reuseRequested() {
        synchronized (LOCK) {
            return current != null && !current.closed && current.reuse;
        }
    }

    public static <T extends Resource> Lease<T> acquire(String key, CheckedSupplier<T> factory) throws Exception {
        ScenarioScope scope;
        Entry obsolete;
        synchronized (LOCK) {
            scope = current;
            if (scope == null || scope.closed) {
                throw new IllegalStateException("A broker lease requires an active snapshot scenario.");
            }
            if (scope.reuse && (scope.acquiring || !scope.leases.isEmpty())) {
                throw new IllegalArgumentException("A reusable snapshot scenario must own exactly one broker lease.");
            }
            if (scope.reuse && cached != null && cached.key.equals(key)) {
                Lease<T> lease = new Lease<>(cached, scope, true);
                scope.leases.add(lease);
                REUSED.merge(key, 1L, Long::sum);
                LOG.info("Snapshot broker {}: reused for {}.", key, scope.name);
                return lease;
            }
            obsolete = cached;
            cached = null;
            scope.acquiring = true;
        }

        Entry entry;
        try {
            destroy(obsolete);
            entry = new Entry(key, factory.get());
        } finally {
            synchronized (LOCK) {
                scope.acquiring = false;
            }
        }
        synchronized (LOCK) {
            CREATED.merge(key, 1L, Long::sum);
            boolean reusable = scope.reuse && !scope.closed && current == scope;
            Lease<T> lease = new Lease<>(entry, scope, reusable);
            lease.discard = scope.closed;
            scope.leases.add(lease);
            if (reusable) {
                cached = entry;
            }
            LOG.info("Snapshot broker {}: created for {} (reusable: {}).", key, scope.name, reusable);
            return lease;
        }
    }

    public static void closeWorker() throws Exception {
        Entry obsolete;
        synchronized (LOCK) {
            if (current != null) {
                throw new IllegalStateException("A snapshot broker scenario is still active at worker shutdown.");
            }
            obsolete = cached;
            cached = null;
        }
        destroy(obsolete);
        synchronized (LOCK) {
            LOG.info("Snapshot broker worker totals: created={}, reused={}, closed={}.", CREATED, REUSED, CLOSED);
            if (!CREATED.equals(CLOSED)) {
                throw new IllegalStateException("Snapshot broker resources remain open: created=" + CREATED + ", closed=" + CLOSED);
            }
        }
    }

    private static void destroy(Entry entry) throws Exception {
        if (entry == null) {
            return;
        }
        synchronized (LOCK) {
            if (entry.destroyed) {
                return;
            }
            entry.destroyed = true;
        }
        entry.resource.close();
        synchronized (LOCK) {
            CLOSED.merge(entry.key, 1L, Long::sum);
        }
        LOG.info("Snapshot broker {}: closed.", entry.key);
    }

    public static final class ScenarioScope implements AutoCloseable {
        private final String name;
        private final boolean reuse;
        private final List<Lease<?>> leases = new ArrayList<>();
        private boolean acquiring;
        private boolean committed;
        private boolean closed;

        private ScenarioScope(String name, boolean reuse) {
            this.name = name;
            this.reuse = reuse;
        }

        public void commit() {
            synchronized (LOCK) {
                if (reuse && leases.size() != 1) {
                    throw new IllegalStateException("A broker-reuse scenario did not acquire its broker lease: " + name);
                }
                if (leases.stream().anyMatch(lease -> !lease.closed)) {
                    throw new IllegalStateException("Snapshot broker leases remain open after scenario cleanup: " + name);
                }
                committed = true;
            }
        }

        @Override
        public void close() throws Exception {
            Entry obsolete = null;
            synchronized (LOCK) {
                closed = true;
                if (!committed) {
                    for (Lease<?> lease : leases) {
                        lease.discard = true;
                    }
                    if (cached != null) {
                        boolean stillOwned = leases.stream().anyMatch(lease -> lease.entry == cached && !lease.closed);
                        if (!stillOwned) {
                            obsolete = cached;
                        }
                        cached = null;
                    }
                }
                if (current == this) {
                    current = null;
                }
            }
            destroy(obsolete);
        }
    }

    public static final class Lease<T extends Resource> implements AutoCloseable {
        private final Entry entry;
        private final ScenarioScope scope;
        private final boolean reusable;
        private boolean discard;
        private boolean closing;
        private boolean closed;

        private Lease(Entry entry, ScenarioScope scope, boolean reusable) {
            this.entry = entry;
            this.scope = scope;
            this.reusable = reusable;
        }

        @SuppressWarnings("unchecked")
        public T resource() {
            return (T) entry.resource;
        }

        @Override
        public void close() throws Exception {
            boolean reset;
            synchronized (LOCK) {
                if (closed || closing) {
                    return;
                }
                closing = true;
                reset = reusable && !discard && !scope.closed;
            }
            try {
                if (!reset) {
                    destroy(entry);
                    return;
                }
                try {
                    entry.resource.resetAfterScenario();
                } catch (Exception | Error failure) {
                    synchronized (LOCK) {
                        if (cached == entry) {
                            cached = null;
                        }
                    }
                    try {
                        destroy(entry);
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                boolean destroy;
                synchronized (LOCK) {
                    destroy = discard || scope.closed || cached != entry;
                }
                if (destroy) {
                    destroy(entry);
                } else {
                    LOG.info("Snapshot broker {}: reset after {}.", entry.key, scope.name);
                }
            } finally {
                synchronized (LOCK) {
                    closed = true;
                }
            }
        }
    }

    private static final class Entry {
        private final String key;
        private final Resource resource;
        private boolean destroyed;

        private Entry(String key, Resource resource) {
            this.key = key;
            this.resource = resource;
        }
    }
}
