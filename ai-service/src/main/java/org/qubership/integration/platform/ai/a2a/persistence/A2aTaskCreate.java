package org.qubership.integration.platform.ai.a2a.persistence;

import java.time.Instant;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Fields required to create a durable A2A Task. {@code revision} starts at {@code 1}.
 */
public record A2aTaskCreate(
    String taskId,
    String contextId,
    String conversationId,
    A2aTaskState state,
    String tenantId,
    String subjectId,
    String publicSnapshotJson,
    String messageHistoryJson,
    String artifactMetadataJson,
    Instant finalizedAt) {}
