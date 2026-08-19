package org.qubership.integration.platform.ai.chain.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * What an edit request asks the compiler to do to an imported chain.
 *
 * <p>Configuration is split by owner rather than kept as one "change a setting" action, because the
 * compiler package gives authentication, timeouts, retries and security to different skills, each
 * with its own ownership contract. Naming the owner here is what keeps a timeout request from
 * reaching the skill that may rewrite credentials.
 */
public enum ChainEditAction {
  /** The capture names no change. Emit this value; never an empty string. */
  NO_CHANGE,
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
  UNRESOLVED;

  /**
   * Maps a capture value onto this enum. Blank or unknown names become {@link #NO_CHANGE} so the
   * parser does not throw when the model omits a real action.
   */
  @JsonCreator
  public static ChainEditAction fromCaptureValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return NO_CHANGE;
    }
    String normalized =
        raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    for (ChainEditAction value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    return NO_CHANGE;
  }
}
