package org.qubership.integration.platform.ai.flow.persistence;

import io.quarkiverse.flow.persistence.jpa.CompletedTaskEntity;
import io.quarkiverse.flow.persistence.jpa.RetriedTaskEntity;
import io.quarkiverse.flow.persistence.jpa.TaskInfoEntity;
import io.serverlessworkflow.impl.persistence.CompletedTaskInfo;
import io.serverlessworkflow.impl.persistence.PersistenceTaskInfo;
import io.serverlessworkflow.impl.persistence.RetriedTaskInfo;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Quarkus Flow 0.14.0 JPA restore maps checkpoints by JSON pointer only. A CONTINUE loop writes
 * another {@code task_info_entity} row with the same pointer and a higher iteration, so {@code
 * Collectors.toMap} throws {@code Duplicate key do/1/routeDecision} during {@code scanAll}. The
 * serverless-workflow reader also keys restored tasks by pointer and keeps one iteration, so the
 * highest iteration is the checkpoint that can resume.
 */
final class LatestIterationTaskInfos {

  private LatestIterationTaskInfos() {}

  static Map<String, PersistenceTaskInfo> merge(Collection<TaskInfoEntity> taskEntities) {
    if (taskEntities == null || taskEntities.isEmpty()) {
      return Map.of();
    }
    return taskEntities.stream()
        .collect(
            Collectors.toMap(
                TaskInfoEntity::jsonPointer,
                entity -> entity,
                LatestIterationTaskInfos::preferLatestEntity))
        .entrySet()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> toPersistence(entry.getValue())));
  }

  static TaskInfoEntity preferLatestEntity(TaskInfoEntity left, TaskInfoEntity right) {
    return right.iteration() >= left.iteration() ? right : left;
  }

  static PersistenceTaskInfo preferLatest(PersistenceTaskInfo left, PersistenceTaskInfo right) {
    return iteration(right) >= iteration(left) ? right : left;
  }

  private static int iteration(PersistenceTaskInfo info) {
    if (info instanceof CompletedTaskInfo completed) {
      return completed.iteration();
    }
    return 0;
  }

  private static PersistenceTaskInfo toPersistence(TaskInfoEntity taskEntity) {
    if (taskEntity instanceof CompletedTaskEntity completed) {
      return new CompletedTaskInfo(
          completed.getInstant(),
          completed.getModel(),
          completed.getContext(),
          completed.isEndNode(),
          completed.getNextPosition(),
          completed.iteration());
    }
    if (taskEntity instanceof RetriedTaskEntity retried) {
      return new RetriedTaskInfo(retried.getRetryAttempt());
    }
    throw new UnsupportedOperationException("Unsupported taskInfo type " + taskEntity.getClass());
  }
}
