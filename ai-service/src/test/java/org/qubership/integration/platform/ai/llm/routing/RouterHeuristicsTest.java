package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouterHeuristicsTest {

  @Test
  void onlyDesignSkipsImplementPhrase() {
    Optional<ScenarioType> r =
        RouterHeuristics.tryFastResolve(
            "Only the IDS document, do not implement yet", "(no history)", Optional.empty(), false);
    assertEquals(Optional.of(ScenarioType.CREATE_DESIGN), r);
  }

  @Test
  void findOperationFastRoutesToCreateChainPlan() {
    Optional<ScenarioType> r =
        RouterHeuristics.tryFastResolve(
            "find placeOrder operation", "(no history)", Optional.empty(), false);
    assertEquals(Optional.of(ScenarioType.CREATE_CHAIN_PLAN), r);
  }

  @Test
  void bareAgreeDoesNotFastResolve() {
    Optional<ScenarioType> r =
        RouterHeuristics.tryFastResolve(
            "Agree",
            "Assistant: Approve?",
            Optional.of(
                new ActiveChainPlanSnapshot(
                    "p1",
                    "c1",
                    false,
                    "bare http",
                    new ChainImplementationPlan(),
                    Instant.now(),
                    List.of(),
                    List.of())),
            true);
    assertEquals(Optional.empty(), r);
  }
}
