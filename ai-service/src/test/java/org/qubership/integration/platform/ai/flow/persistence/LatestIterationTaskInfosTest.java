package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkiverse.flow.persistence.jpa.CompletedTaskEntity;
import io.quarkiverse.flow.persistence.jpa.TaskInfoKey;
import io.serverlessworkflow.impl.persistence.CompletedTaskInfo;
import io.serverlessworkflow.impl.persistence.PersistenceTaskInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LatestIterationTaskInfosTest {

  @Test
  void mergeKeepsTheHighestIterationForARepeatedRouteDecisionPointer() {
    CompletedTaskEntity first =
        completed("do/1/routeDecision", 1, "do/2/waitForInput", Instant.parse("2026-08-14T07:40:06Z"));
    CompletedTaskEntity latest =
        completed(
            "do/1/routeDecision", 4, "do/8/waitForIdsApproval", Instant.parse("2026-08-14T07:40:07Z"));

    Map<String, PersistenceTaskInfo> merged = LatestIterationTaskInfos.merge(List.of(first, latest));

    assertEquals(1, merged.size());
    CompletedTaskInfo kept = (CompletedTaskInfo) merged.get("do/1/routeDecision");
    assertEquals(4, kept.iteration());
    assertEquals("do/8/waitForIdsApproval", kept.nextPosition());
  }

  @Test
  void preferLatestKeepsTheHigherCompletedIteration() {
    CompletedTaskInfo left =
        new CompletedTaskInfo(Instant.EPOCH, null, null, false, "do/2/waitForInput", 1);
    CompletedTaskInfo right =
        new CompletedTaskInfo(Instant.EPOCH, null, null, false, "do/0/executeStage", 2);

    assertSame(right, LatestIterationTaskInfos.preferLatest(left, right));
    assertSame(right, LatestIterationTaskInfos.preferLatest(right, left));
  }

  private static CompletedTaskEntity completed(
      String jsonPointer, int iteration, String nextPosition, Instant instant) {
    TaskInfoKey key = new TaskInfoKey();
    key.setApplicationId("qip-ai-assistant-2");
    key.setWorkflowInstanceId("instance-1");
    key.setJsonPointer(jsonPointer);
    key.setIteration(iteration);
    return new CompletedTaskEntity(key, instant, null, null, false, nextPosition);
  }
}
