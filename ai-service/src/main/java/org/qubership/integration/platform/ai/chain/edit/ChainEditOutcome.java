package org.qubership.integration.platform.ai.chain.edit;

import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/**
 * What preparing one edit produced. Every branch is read-only: no outcome here has touched the
 * runtime catalog, and only {@link Proposal} can become a decision card.
 */
public sealed interface ChainEditOutcome {

  /**
   * A compiled, validated change.
   *
   * @param netPatch the single change from {@code baseGraph} to {@code finalGraph}; the card, the
   *     proposal digest, the removal closure, and the writer all read this and nothing else
   */
  record Proposal(
      GraphPatch netPatch,
      ChainPlanGraph baseGraph,
      ChainPlanGraph finalGraph,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      List<String> executedSkillIds,
      RunManifest runManifest)
      implements ChainEditOutcome {

    public Proposal {
      bindings = bindings == null ? List.of() : List.copyOf(bindings);
      executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    }
  }

  /** The request fits more than one reading. The reader answers; nothing is written meanwhile. */
  record Clarification(String question, List<String> choices) implements ChainEditOutcome {
    public Clarification {
      choices = choices == null ? List.of() : List.copyOf(choices);
    }
  }

  /** Nothing in the chain or the catalog answers the request, and nothing was invented. */
  record ResolutionFailure(String message) implements ChainEditOutcome {}

  /** The compiler ran and refused the result: ownership, schema, or validation. */
  record CompilationFailure(String message) implements ChainEditOutcome {}

  /** Going further would create catalog artifacts, which needs the reader to say so first. */
  record Escalation(String message) implements ChainEditOutcome {}

  /**
   * No compiler skill owns this edit yet, so the caller falls back to the model-authored patch path.
   * This branch disappears once every edit kind is migrated.
   */
  record Unsupported(ChainEditAction action) implements ChainEditOutcome {}
}
