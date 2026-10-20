package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.List;

/** Identifies an author choice after reviewing the saved inputs. */
public record AuthorQuestionEvidence(
    String interactionId, String propertyKey, Kind kind, List<String> reviewedInputIds) {
  public enum Kind { MISSING_VALUE, CONFLICTING_VALUES, POLICY_CHOICE, CATALOG_CHOICE, AMBIGUOUS_ANSWER }

  public AuthorQuestionEvidence {
    reviewedInputIds = reviewedInputIds == null ? List.of() : List.copyOf(reviewedInputIds);
  }
}
