package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.DeployOp;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.InfoNeed;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.TurnReferent;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner.Capture;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner.Kind;

class OpenChainTurnPlannerTest {

  @Test
  void invalidDeployPlanFailsClosedAsAQuestion() {
    OpenChainTurnPlan plan =
        OpenChainTurnPlanner.validate(
            new Capture(
                Kind.DEPLOY,
                TurnReferent.LAST_TURN,
                List.of(InfoNeed.DEPLOYMENTS),
                DeployOp.NONE,
                AnswerShape.EXPLAIN));

    OpenChainTurnPlan.Ask ask = assertInstanceOf(OpenChainTurnPlan.Ask.class, plan);
    assertEquals(TurnReferent.LAST_TURN, ask.referent());
    assertEquals(AnswerShape.EXPLAIN, ask.answerShape());
    assertEquals(java.util.Set.of(InfoNeed.DEPLOYMENTS), ask.needs());
  }

  @Test
  void deterministicAnswerAlwaysRequestsFacts() {
    OpenChainTurnPlan plan =
        OpenChainTurnPlanner.validate(
            new Capture(
                Kind.ASK,
                TurnReferent.OPEN_CHAIN,
                List.of(),
                DeployOp.NONE,
                AnswerShape.JSON));

    OpenChainTurnPlan.Ask ask = assertInstanceOf(OpenChainTurnPlan.Ask.class, plan);
    assertTrue(ask.needs().contains(InfoNeed.FACTS));
  }

  @Test
  void nullEntriesFromStructuredOutputAreIgnored() {
    Capture capture =
        new Capture(
            Kind.ASK,
            TurnReferent.OPEN_CHAIN,
            java.util.Arrays.asList(InfoNeed.SNAPSHOTS, null),
            DeployOp.NONE,
            AnswerShape.EXPLAIN);

    OpenChainTurnPlan.Ask ask =
        assertInstanceOf(OpenChainTurnPlan.Ask.class, OpenChainTurnPlanner.validate(capture));
    assertEquals(java.util.Set.of(InfoNeed.SNAPSHOTS), ask.needs());
  }
}
