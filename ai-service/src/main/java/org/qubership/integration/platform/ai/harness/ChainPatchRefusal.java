package org.qubership.integration.platform.ai.harness;

/**
 * Why a chain patch did not reach the catalog whole.
 *
 * <p>These read very differently to whoever has to act on them, and a report that collapses them
 * into one flag sends a reader looking for a permissions problem when the patch was simply
 * malformed.
 */
public enum ChainPatchRefusal {
  /** Nothing was refused. */
  NONE,
  /** The patch asked to change something this skill does not own. */
  OWNERSHIP,
  /** The patch is malformed: the applier could not apply it to the graph. */
  STRUCTURAL,
  /** The patch applies, but would leave the chain broken. */
  SEMANTIC,
  /** The patch was accepted and the catalog write failed. */
  WRITE
}
