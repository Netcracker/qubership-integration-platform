package org.qubership.integration.platform.ai.chain.edit;

import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * The chain a structure capture is editing, published for capture-time validation.
 *
 * <p>A structure capture for an edit describes a chain that already exists, so the graph worth
 * checking is the merge of the capture onto that chain, not the capture on its own. The compiler
 * publishes this before the structure stage runs and clears it afterwards; a CREATE run leaves it
 * absent and its capture is validated as the whole graph it is.
 */
public record ChainEditStructureBase(ChainPlanGraph baseGraph, ChainEditIntent intent) {

  public ChainEditStructureBase {
    Objects.requireNonNull(baseGraph, "baseGraph");
    Objects.requireNonNull(intent, "intent");
  }
}
