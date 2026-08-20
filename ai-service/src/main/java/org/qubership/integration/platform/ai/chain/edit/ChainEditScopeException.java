package org.qubership.integration.platform.ai.chain.edit;

/**
 * A structure capture refused because it falls outside the scope the intent approved.
 *
 * <p>Raised by {@link ChainEditSubgraphAssembly} for a capture that describes a change the intent
 * did not name, and by {@link ChainEditStructureMerge} for the whole-graph captures that have yet
 * to move onto the subgraph contract.
 *
 * <p>Extends {@link IllegalArgumentException} so callers that already treat a refused merge as a
 * bad argument keep working unchanged.
 *
 * <p>{@link #unsatisfiable()} separates the two kinds, because they deserve opposite retry
 * budgets. A satisfiable refusal describes a capture the generator can correct and resubmit: it
 * moved a node it should have left alone, or rewrote an edge outside the target boundary. An
 * unsatisfiable one describes an intent no capture can meet, so asking the generator again only
 * spends budget on a request that cannot succeed.
 */
public class ChainEditScopeException extends IllegalArgumentException {

  private final boolean unsatisfiable;

  ChainEditScopeException(String message, boolean unsatisfiable) {
    super(message);
    this.unsatisfiable = unsatisfiable;
  }

  /** Whether no capture can satisfy this intent, so a repair turn cannot recover the edit. */
  public boolean unsatisfiable() {
    return unsatisfiable;
  }
}
