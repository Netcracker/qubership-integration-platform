package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Slice 1: Task round trip through PostgreSQL.
 */
@QuarkusTest
class A2aTaskRepositoryIT {

  @Inject A2aTaskRepository taskRepository;

  @Test
  void persistsAndReloadsTaskWithHistoryStatusAndArtifacts() {
    String taskId = "task-" + UUID.randomUUID();
    A2aTaskCreate create =
        new A2aTaskCreate(
            taskId,
            "ctx-" + taskId,
            taskId,
            A2aTaskState.WORKING,
            "tenant-a",
            "subject-a",
            "{\"id\":\"" + taskId + "\",\"status\":\"WORKING\"}",
            "[{\"role\":\"user\",\"text\":\"build a chain\"}]",
            "[{\"artifactId\":\"a1\",\"type\":\"requirement-draft\",\"revision\":1}]",
            null);

    A2aPersistedTask inserted = taskRepository.insert(create);
    Optional<A2aPersistedTask> reloaded = taskRepository.findByTaskId(taskId);

    assertTrue(reloaded.isPresent());
    A2aPersistedTask task = reloaded.get();
    assertEquals(taskId, task.taskId());
    assertEquals(create.contextId(), task.contextId());
    assertEquals(taskId, task.conversationId());
    assertEquals(A2aTaskState.WORKING, task.state());
    assertEquals(1L, task.revision());
    assertEquals("tenant-a", task.tenantId());
    assertEquals("subject-a", task.subjectId());
    assertEquals(create.publicSnapshotJson(), task.publicSnapshotJson());
    assertEquals(create.messageHistoryJson(), task.messageHistoryJson());
    assertEquals(create.artifactMetadataJson(), task.artifactMetadataJson());
    assertNull(task.finalizedAt());
    assertEquals(inserted.createdAt(), task.createdAt());
  }
}
