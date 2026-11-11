package org.qubership.integration.platform.ai.a2a.persistence;

import java.time.Instant;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Durable A2A Task row owned by the application (not the product-pipeline runtime).
 * Snapshot, history, and artifact fields are opaque JSON strings produced by later adapters.
 */
public record A2aPersistedTask(
    String taskId,
    String contextId,
    String conversationId,
    A2aTaskState state,
    long revision,
    String tenantId,
    String subjectId,
    String publicSnapshotJson,
    String messageHistoryJson,
    String artifactMetadataJson,
    Instant createdAt,
    Instant updatedAt,
    Instant finalizedAt) {}
