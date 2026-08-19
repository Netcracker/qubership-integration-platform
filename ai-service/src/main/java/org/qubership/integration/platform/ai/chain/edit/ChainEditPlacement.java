package org.qubership.integration.platform.ai.chain.edit;

/**
 * Where a captured {@link ChainEditAction#ADD_ELEMENTS} lands on the imported graph.
 *
 * <p>Java applies this field. It does not infer it from the element type name or from English in the
 * request.
 */
public enum ChainEditPlacement {
  /** Not an addition, or the capture left placement empty. */
  UNSET,
  /** A new trigger at chain root, fanning into the start existing triggers already share. */
  ROOT_TRIGGER,
  /** Insert a shell after the named target elements. */
  AFTER_TARGET,
  /** The shared structure stage places a container, wrap, or branch. */
  GENERATOR
}
