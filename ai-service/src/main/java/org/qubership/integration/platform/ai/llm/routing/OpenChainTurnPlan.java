package org.qubership.integration.platform.ai.llm.routing;

import java.util.LinkedHashSet;
import java.util.Set;
import org.qubership.integration.platform.ai.model.ScenarioType;

/** A validated open-chain turn. Its variants exclude contradictory read and write instructions. */
public sealed interface OpenChainTurnPlan
    permits OpenChainTurnPlan.Ask, OpenChainTurnPlan.Patch, OpenChainTurnPlan.Deploy {

  ScenarioType scenario();

  record Ask(TurnReferent referent, Set<InfoNeed> needs, AnswerShape answerShape)
      implements OpenChainTurnPlan {

    public Ask {
      referent = referent == null ? TurnReferent.OPEN_CHAIN : referent;
      answerShape = answerShape == null ? AnswerShape.EXPLAIN : answerShape;
      LinkedHashSet<InfoNeed> normalized =
          new LinkedHashSet<>(needs == null ? Set.of() : needs);
      if (answerShape != AnswerShape.EXPLAIN) {
        normalized.add(InfoNeed.FACTS);
      }
      if (referent == TurnReferent.OPEN_CHAIN && normalized.isEmpty()) {
        normalized.add(InfoNeed.FACTS);
      }
      needs = Set.copyOf(normalized);
    }

    @Override
    public ScenarioType scenario() {
      return ScenarioType.ASK_CHAIN;
    }
  }

  record Patch() implements OpenChainTurnPlan {
    @Override
    public ScenarioType scenario() {
      return ScenarioType.COMPARE_AND_PATCH;
    }
  }

  record Deploy(DeployOp operation) implements OpenChainTurnPlan {
    public Deploy {
      if (operation == null || operation == DeployOp.NONE) {
        throw new IllegalArgumentException("A deployment mutation requires an operation");
      }
    }

    @Override
    public ScenarioType scenario() {
      return ScenarioType.DEPLOY_CHAIN;
    }
  }

  enum TurnReferent {
    LAST_TURN,
    OPEN_CHAIN
  }

  enum InfoNeed {
    FACTS,
    SNAPSHOTS,
    DEPLOYMENTS
  }

  enum DeployOp {
    NONE,
    CREATE_SNAPSHOT,
    DEPLOY,
    UNDEPLOY
  }

  enum AnswerShape {
    EXPLAIN,
    GRAPH,
    JSON,
    TREE,
    SCRIPT
  }
}
