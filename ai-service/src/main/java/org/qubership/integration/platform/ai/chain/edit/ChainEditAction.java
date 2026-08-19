package org.qubership.integration.platform.ai.chain.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * What an edit request asks the compiler to do to an imported chain.
 *
 * <p>Every configuration change -- a script body, authentication, a timeout, a retry, a security
 * setting, or any other property family -- travels as {@code CONFIGURE}. The reader names the
 * property keys, and the owner is whichever generator the pinned compiler package declares them
 * for. No action value here names a generator: routing comes from the pinned package's ownership
 * metadata, read once in {@link ChainEditCapabilitySelection}, rather than from a family enumerated
 * in this enum.
 */
public enum ChainEditAction {
  /** The capture names no change. Emit this value; never an empty string. */
  NO_CHANGE,
  /** Point an existing service-call at another catalog operation. */
  REBIND_SERVICE_CALL,
  /**
   * Change a property the pinned compiler package declares an owner for, named by property key
   * rather than by a family enumerated in this enum.
   */
  CONFIGURE,
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
