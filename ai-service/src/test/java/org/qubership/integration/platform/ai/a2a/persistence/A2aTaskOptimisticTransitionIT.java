package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Slice 2: optimistic concurrency rejects a stale revision.
 */
@QuarkusTest
class A2aTaskOptimisticTransitionIT {

  @Inject A2aTaskRepository taskRepository;

  @Test
  void exactlyOneWriterWinsWhenTwoUpdateSameRevision() throws Exception {
    String taskId = "task-opt-" + UUID.randomUUID();
    taskRepository.insert(
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            null,
            null,
            "{\"id\":\"" + taskId + "\"}",
            "[]",
            "[]",
            null));

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<A2aTaskTransitionResult>> futures = new ArrayList<>();
      for (int writer = 0; writer < 2; writer++) {
        int writerId = writer;
        futures.add(
            pool.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return taskRepository.transition(
                      taskId,
                      new A2aTaskUpdate(
                          1L,
                          A2aTaskState.INPUT_REQUIRED,
                          "{\"id\":\"" + taskId + "\",\"writer\":" + writerId + "}",
                          "[{\"writer\":" + writerId + "}]",
                          "[]",
                          null));
                }));
      }
      start.countDown();

      A2aTaskTransitionResult first = futures.get(0).get(10, TimeUnit.SECONDS);
      A2aTaskTransitionResult second = futures.get(1).get(10, TimeUnit.SECONDS);
      List<A2aTaskTransitionResult> results = List.of(first, second);

      long applied =
          results.stream().filter(A2aTaskTransitionResult.Applied.class::isInstance).count();
      long stale =
          results.stream().filter(A2aTaskTransitionResult.StaleRevision.class::isInstance).count();
      assertEquals(1L, applied, "exactly one writer must apply");
      assertEquals(1L, stale, "the other writer must see a typed stale-revision result");

      A2aPersistedTask current = taskRepository.findByTaskId(taskId).orElseThrow();
      assertEquals(2L, current.revision());
      assertEquals(A2aTaskState.INPUT_REQUIRED, current.state());
      assertTrue(
          results.stream().anyMatch(A2aTaskTransitionResult.StaleRevision.class::isInstance));
    } finally {
      pool.shutdownNow();
    }
  }
}
