package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.jdbc.JdbcA2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Slice 4: Task snapshot survives repository recreation over the same PostgreSQL database.
 */
@QuarkusTest
class A2aTaskRestartIT {

  @Inject DataSource dataSource;

  @Test
  void inputRequiredTaskSurvivesFreshRepositoryInstance() {
    A2aTaskRepository first = new JdbcA2aTaskRepository(dataSource);
    String taskId = "task-restart-" + UUID.randomUUID();
    String snapshot =
        "{\"id\":\"" + taskId + "\",\"state\":\"INPUT_REQUIRED\",\"pending\":\"clarify\"}";

    first.insert(
        new A2aTaskCreate(
            taskId,
            "ctx-restart",
            taskId,
            A2aTaskState.INPUT_REQUIRED,
            "local",
            "local-user",
            snapshot,
            "[{\"role\":\"user\",\"text\":\"need approval\"}]",
            "[{\"artifactId\":\"plan-1\",\"type\":\"implementation-plan\",\"revision\":2}]",
            null));

    A2aTaskRepository recreated = new JdbcA2aTaskRepository(dataSource);
    A2aPersistedTask reloaded = recreated.findByTaskId(taskId).orElseThrow();

    assertEquals(taskId, reloaded.taskId());
    assertEquals(A2aTaskState.INPUT_REQUIRED, reloaded.state());
    assertEquals(snapshot, reloaded.publicSnapshotJson());
    assertEquals("local", reloaded.tenantId());
    assertEquals("local-user", reloaded.subjectId());
    assertTrue(reloaded.finalizedAt() == null);
  }
}
