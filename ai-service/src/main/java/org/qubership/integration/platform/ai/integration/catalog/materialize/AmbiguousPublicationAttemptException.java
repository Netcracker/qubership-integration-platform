package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.List;

/** Multiple catalog chains match the same publication-attempt label. */
public final class AmbiguousPublicationAttemptException extends RuntimeException {

  private final String attemptLabel;
  private final List<String> matchingChainIds;

  public AmbiguousPublicationAttemptException(String attemptLabel, List<String> matchingChainIds) {
    super(
        "Ambiguous publication attempt for label "
            + attemptLabel
            + ": matching chain IDs "
            + matchingChainIds);
    this.attemptLabel = attemptLabel;
    this.matchingChainIds = List.copyOf(matchingChainIds);
  }

  public AmbiguousPublicationAttemptException(
      String attemptLabel, List<String> matchingChainIds, Throwable cause) {
    super(
        "Ambiguous publication attempt for label "
            + attemptLabel
            + ": matching chain IDs "
            + matchingChainIds,
        cause);
    this.attemptLabel = attemptLabel;
    this.matchingChainIds = List.copyOf(matchingChainIds);
  }

  public String attemptLabel() {
    return attemptLabel;
  }

  public List<String> matchingChainIds() {
    return matchingChainIds;
  }
}
