package org.qubership.integration.platform.ai.chain.folder;

import java.util.List;

/** A bare folder name that more than one folder in the catalog carries. */
public class AmbiguousFolderException extends RuntimeException {

  private final List<String> candidatePaths;

  public AmbiguousFolderException(String reference, List<String> candidatePaths) {
    super(
        "Folder name '"
            + reference
            + "' matches several folders: "
            + String.join(", ", candidatePaths)
            + ". Give the full path from the root.");
    this.candidatePaths = List.copyOf(candidatePaths);
  }

  public List<String> candidatePaths() {
    return candidatePaths;
  }
}
