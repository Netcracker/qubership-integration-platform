package org.qubership.integration.platform.ai.a2a.persistence;

import java.time.Instant;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Optimistic Task update. Applied only when the stored revision equals {@code expectedRevision}.
 */
public record A2aTaskUpdate(
    long expectedRevision,
    A2aTaskState state,
    String publicSnapshotJson,
    String messageHistoryJson,
    String artifactMetadataJson,
    Instant finalizedAt) {}
