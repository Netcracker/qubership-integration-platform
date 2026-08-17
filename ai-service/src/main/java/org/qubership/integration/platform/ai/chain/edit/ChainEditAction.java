package org.qubership.integration.platform.ai.chain.edit;

/** What an edit request asks the compiler to do to an imported chain. */
public enum ChainEditAction {
  /** Point an existing service-call at another catalog operation. */
  REBIND_SERVICE_CALL,
  /** Rewrite the body of an existing script element. */
  EDIT_SCRIPT,
  /** Change configuration owned by a domain skill (timeout, retry, authentication, security). */
  EDIT_CONFIGURATION,
  /** Place new elements relative to the imported graph. */
  ADD_ELEMENTS,
  /** Remove elements and everything the catalog cascades with them. */
  DELETE,
  /** Cut connections without removing the elements they join. */
  DISCONNECT,
  /** Change the priority order of container branches. */
  REORDER
}
