package org.qubership.integration.platform.engine.routes.entrypoint.bundle;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SnapshotBundleEntry(
        String chainName,
        String chainId,
        String snapshotId,
        Path snapshotFile,
        Map<String, String> snapshotNodeIdsBySourceElementId
) {
    public SnapshotBundleEntry {
        snapshotNodeIdsBySourceElementId = Collections.unmodifiableMap(
                new LinkedHashMap<>(snapshotNodeIdsBySourceElementId)
        );
    }
}
