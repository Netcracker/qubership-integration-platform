package org.qubership.integration.platform.ai.chain.patch;

import java.util.List;

/**
 * What reached the catalog when a chain patch was written.
 *
 * <p>A patch that fails partway is reported, not unwound: {@code changedElementIds} names what did
 * land, so the reader is told the chain's real state rather than that nothing happened.
 */
public record ChainPatchWriteResult(
    List<String> changedElementIds, List<String> failedElementIds, String error) {

  public ChainPatchWriteResult {
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
  }

  public boolean succeeded() {
    return failedElementIds.isEmpty() && error == null;
  }
}
