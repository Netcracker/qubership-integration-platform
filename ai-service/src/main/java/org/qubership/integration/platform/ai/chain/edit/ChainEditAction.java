package org.qubership.integration.platform.ai.chain.edit;

/**
 * What an edit request asks the compiler to do to an imported chain.
 *
 * <p>Configuration is split by owner rather than kept as one "change a setting" action, because the
 * compiler package gives authentication, timeouts, retries and security to different skills, each
 * with its own ownership contract. Naming the owner here is what keeps a timeout request from
 * reaching the skill that may rewrite credentials.
 */
public enum ChainEditAction {
  /** Point an existing service-call at another catalog operation. */
  REBIND_SERVICE_CALL,
  /** Rewrite the body of an existing script element. */
  EDIT_SCRIPT,
  /** Change how an element authenticates. */
  EDIT_AUTHENTICATION,
  /** Change how long an element waits. */
  EDIT_TIMEOUT,
  /** Change how an element retries. */
  EDIT_RETRY,
  /** Change an element's security settings. */
  EDIT_SECURITY,
  /** Place new elements relative to the imported graph. */
  ADD_ELEMENTS,
  /** Remove elements and everything the catalog cascades with them. */
  DELETE,
  /** Cut connections without removing the elements they join. */
  DISCONNECT,
  /** Change the priority order of container branches. */
  REORDER,
  /** The request did not resolve to one action. */
  UNRESOLVED
}
