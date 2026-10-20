package org.qubership.integration.platform.ai.plan;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * What one requirement-flow interaction resolved to, and on what evidence.
 *
 * <p>One assessment per interaction. {@code interactionId} is the key: resolving one interaction
 * leaves the others alone.
 *
 * <p>The outcome is explicit. A missing binding used to mean anything from "the catalog has no such
 * operation" to "nobody asked yet", and the difference decides whether an APIHub search is allowed.
 */
public record InteractionAssessment(
    String interactionId,
    Intent intent,
    Outcome outcome,
    CatalogMatch binding,
    List<String> candidateOperationIds,
    List<String> missingIntentFields,
    String evidenceRef,
    Instant observedAt) {

  /** How the catalog answered for one interaction. */
  public enum Outcome {
    /** Exactly one catalog operation matched; the binding is frozen. */
    RESOLVED,
    /** Several catalog operations matched; the author has to choose. */
    AMBIGUOUS,
    /** The catalog holds no such operation. This is what authorizes an APIHub search. */
    CATALOG_MISS,
    /** The intent lacks the identity fields a catalog lookup needs. */
    INCOMPLETE
  }

  /**
   * The identity of one interaction, as fields rather than as one sentence.
   *
   * <p>{@code capability} is what the author wants done and stays free text. The hints are what the
   * catalog can be searched by; a search built from the whole fact sentence matches by accident.
   */
  public record Intent(
      String capability,
      String systemHint,
      String operationHint,
      String method,
      String path,
      String specificationHint) {

    /** Keeps the five-field form callers used before specifications could be named. */
    public Intent(
        String capability, String systemHint, String operationHint, String method, String path) {
      this(capability, systemHint, operationHint, method, path, null);
    }

    public Intent {
      capability = CatalogStrings.blankToNull(capability);
      systemHint = CatalogStrings.blankToNull(systemHint);
      operationHint = CatalogStrings.blankToNull(operationHint);
      method = normalizeMethod(method);
      path = CatalogStrings.blankToNull(path);
      specificationHint = CatalogStrings.blankToNull(specificationHint);
    }

    /** Catalog query for this intent: method and path when both are known, the hint otherwise. */
    public String operationQuery() {
      if (method != null && path != null) {
        return method + " " + path;
      }
      return operationHint != null ? operationHint : capability;
    }

    /** Identity fields a catalog lookup needs and this intent does not have. */
    public List<String> missingFields() {
      if (operationHint != null || (method != null && path != null)) {
        return List.of();
      }
      if (method != null) {
        return List.of("path");
      }
      if (path != null) {
        return List.of("method");
      }
      return List.of("operationHint", "method", "path");
    }

    private static String normalizeMethod(String method) {
      String trimmed = CatalogStrings.blankToNull(method);
      return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
  }

  public InteractionAssessment {
    interactionId = Objects.requireNonNull(CatalogStrings.blankToNull(interactionId), "interactionId");
    intent = Objects.requireNonNull(intent, "intent");
    outcome = Objects.requireNonNull(outcome, "outcome");
    candidateOperationIds =
        candidateOperationIds == null ? List.of() : List.copyOf(candidateOperationIds);
    missingIntentFields =
        missingIntentFields == null ? List.of() : List.copyOf(missingIntentFields);
    observedAt = observedAt == null ? Instant.now() : observedAt;
    if (outcome == Outcome.RESOLVED && binding == null) {
      throw new IllegalArgumentException("a resolved assessment needs a binding");
    }
  }

  public static InteractionAssessment resolved(
      String interactionId, Intent intent, CatalogMatch binding) {
    return new InteractionAssessment(
        interactionId,
        intent,
        Outcome.RESOLVED,
        binding,
        List.of(),
        List.of(),
        binding.evidenceRef(),
        Instant.now());
  }

  public static InteractionAssessment ambiguous(
      String interactionId, Intent intent, List<String> candidateOperationIds) {
    return new InteractionAssessment(
        interactionId,
        intent,
        Outcome.AMBIGUOUS,
        null,
        candidateOperationIds,
        List.of(),
        null,
        Instant.now());
  }

  public static InteractionAssessment catalogMiss(String interactionId, Intent intent) {
    return new InteractionAssessment(
        interactionId, intent, Outcome.CATALOG_MISS, null, List.of(), List.of(), null, Instant.now());
  }

  /**
   * The catalog holds too many services to read, so it has answered neither yes nor no.
   *
   * <p>Recorded as {@link Outcome#INCOMPLETE} rather than as a miss on purpose: a miss authorizes
   * an API Hub search, and importing a specification the catalog may already hold is the one
   * outcome a too-broad answer must not produce. What is missing is a narrowing fact, so the caller
   * asks for one exactly as it does for an intent that never had the fields.
   */
  public static InteractionAssessment tooBroad(
      String interactionId, Intent intent, List<String> missingFields) {
    return new InteractionAssessment(
        interactionId, intent, Outcome.INCOMPLETE, null, List.of(), missingFields, null, Instant.now());
  }

  public static InteractionAssessment incomplete(String interactionId, Intent intent) {
    return new InteractionAssessment(
        interactionId,
        intent,
        Outcome.INCOMPLETE,
        null,
        List.of(),
        intent.missingFields(),
        null,
        Instant.now());
  }

  public boolean isResolved() {
    return outcome == Outcome.RESOLVED;
  }
}
