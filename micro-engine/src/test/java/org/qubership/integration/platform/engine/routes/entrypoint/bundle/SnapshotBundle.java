package org.qubership.integration.platform.engine.routes.entrypoint.bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SnapshotBundle {
    private final List<SnapshotBundleEntry> entries;
    private final Map<String, SnapshotBundleEntry> entriesByChainName;

    SnapshotBundle(List<SnapshotBundleEntry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, SnapshotBundleEntry> entriesByChainName = new LinkedHashMap<>();
        this.entries.forEach(entry -> entriesByChainName.put(entry.chainName(), entry));
        this.entriesByChainName = Collections.unmodifiableMap(entriesByChainName);
    }

    List<SnapshotBundleEntry> entries() {
        return entries;
    }

    public Optional<SnapshotBundleEntry> findByChainName(String chainName) {
        return Optional.ofNullable(entriesByChainName.get(chainName));
    }
}
