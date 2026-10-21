package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LatestIterationJpaInstanceOperationsTest {

  @Test
  void materializesAndClosesRepositoryStreamBeforeReturning() {
    AtomicBoolean closed = new AtomicBoolean();
    Stream<String> repositoryRows = Stream.of("one", "two").onClose(() -> closed.set(true));

    List<Integer> materialized =
        LatestIterationJpaInstanceOperations.materialize(repositoryRows, String::length);

    assertEquals(List.of(3, 3), materialized);
    assertTrue(closed.get());
  }
}
