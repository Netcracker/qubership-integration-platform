package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.qubership.integration.platform.ai.plan.NullableCaptureValue;

/** Identifies an author choice after reviewing the saved inputs. */
public record AuthorQuestionEvidence(
    String interactionId,
    String propertyKey,
    Kind kind,
    List<String> reviewedInputIds,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(required = false)
    @NullableCaptureValue(description = "Typed ambiguity evidence from focused answer repair."
        + " Null unless this question stays open as ambiguous.")
    AmbiguityEvidence ambiguity) {
  public enum Kind { MISSING_VALUE, CONFLICTING_VALUES, POLICY_CHOICE, CATALOG_CHOICE, AMBIGUOUS_ANSWER }

  /** Quote and candidates that keep one question open as ambiguous. */
  public record AmbiguityEvidence(String sourceTurnId, String exactQuote, List<String> candidateValues) {
    public AmbiguityEvidence {
      candidateValues = candidateValues == null ? List.of() : List.copyOf(candidateValues);
    }
  }

  /** Keeps legacy JSON and call sites on the four-argument constructor. */
  public AuthorQuestionEvidence(
      String interactionId, String propertyKey, Kind kind, List<String> reviewedInputIds) {
    this(interactionId, propertyKey, kind, reviewedInputIds, null);
  }

  public AuthorQuestionEvidence {
    reviewedInputIds = reviewedInputIds == null ? List.of() : List.copyOf(reviewedInputIds);
  }
}
