package org.qubership.integration.platform.ai.chain.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * What happens to the existing element at the insertion address.
 *
 * <p>Keep, nest, and replace are the same subgraph insertion with a different fate for the
 * element already there. Java applies this field. It does not infer it from English in the
 * request. {@link #UNSET} stays unset for a root trigger. When the capture names address
 * elements and leaves this field empty, Java infers {@link #KEEP}.
 */
public enum ChainEditDisposition {
  /** Not an addition, a root trigger, or the capture left the fate of the address element empty. */
  UNSET,
  /** Leave the address elements where they are and splice the new subgraph beside them. */
  KEEP,
  /** Move the named targets into the new structure. */
  NEST,
  /** Remove the named targets and put the new subgraph in their place. */
  REMOVE,
  /**
   * Add a new branch to a container the chain already has. Nothing moves and nothing is replaced:
   * the single named target is the existing container, and the new branch takes its place beside
   * the container's other branches.
   */
  ATTACH;

  /**
   * Maps a capture value onto this enum. Blank or unknown names become {@link #UNSET} so the
   * parser does not throw when the model omits the field.
   */
  @JsonCreator
  public static ChainEditDisposition fromCaptureValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNSET;
    }
    String normalized =
        raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    for (ChainEditDisposition value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    return UNSET;
  }
}
