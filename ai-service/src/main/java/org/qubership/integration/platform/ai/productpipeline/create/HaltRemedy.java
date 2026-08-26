package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Locale;

/**
 * Closed set of changes the diagnosis turn may prescribe at a recoverable halt. The set is closed
 * so that a halt card cannot propose something the runtime has no way to perform.
 *
 * <p>A remedy is a proposal, never an edit: the runtime prints the sentence that goes with it and
 * leaves the halt-card actions as they are.
 */
public enum HaltRemedy {

  /** No remedy: the evidence supports none, or the model named one outside this set. */
  NONE,

  /** Run the failed stage again on the same inputs. */
  RETRY,

  /** Correct a value or a fact in what the failed stage consumed. */
  REVISE_INPUT,

  /** Go back to the stage that owns the defective artifact. */
  REOPEN_STAGE,

  /** Remove the element the design cannot support. */
  DROP_ELEMENT,

  /** No change on the author's side clears this halt. */
  UNRECOVERABLE;

  /**
   * Maps a model-authored token onto this set. A blank or unknown token becomes {@link #NONE}, so a
   * reply the set does not cover costs the card its remedy sentence and nothing else.
   */
  public static HaltRemedy fromModelValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return NONE;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    for (HaltRemedy value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    return NONE;
  }
}
