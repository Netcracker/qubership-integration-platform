package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;

/**
 * Transport type for a halt: a {@link RecoveryCauseCode} plus the structured findings the producer
 * already had. Travels to recovery routing unmodified.
 */
public record RecoveryCause(
    RecoveryCauseCode causeCode, List<PlanValidationFinding> findings, String requestedFact) {

  public RecoveryCause {
    causeCode = causeCode == null ? RecoveryCauseCode.VALIDATION_BLOCKER : causeCode;
    findings = findings == null ? List.of() : List.copyOf(findings);
    requestedFact = requestedFact == null ? "" : requestedFact;
  }

  public static RecoveryCause of(RecoveryCauseCode causeCode) {
    return new RecoveryCause(causeCode, List.of(), "");
  }

  /** {@code code: message} lines the halt attributes persist and {@link #fromFormattedFindingCodes} rereads. */
  public String formattedFindings() {
    if (findings.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (PlanValidationFinding finding : findings) {
      if (finding == null) {
        continue;
      }
      if (text.length() > 0) {
        text.append('\n');
      }
      text.append(finding.code() == null ? "" : finding.code());
      if (finding.message() != null && !finding.message().isBlank()) {
        text.append(": ").append(finding.message());
      }
    }
    return text.toString();
  }

  /** Requested fact when execution has no catalog binding hint for an interaction. */
  public static final String MISSING_CATALOG_BINDING_FACT = "missing catalog binding";

  /** Requested fact when a stored hint and the semantic occurrence use different identities. */
  public static final String BINDING_IDENTITY_MISMATCH_FACT = "binding identity mismatch";

  public static RecoveryCause catalogResolution(String requestedFact) {
    String fact =
        requestedFact == null || requestedFact.isBlank() ? "catalog service" : requestedFact;
    return new RecoveryCause(RecoveryCauseCode.CATALOG_RESOLUTION, List.of(), fact);
  }

  /**
   * Catalog resolution failed because the interaction has no hint. {@code interactionId} is the
   * unresolved occurrence, not a catalog display name.
   */
  public static RecoveryCause missingCatalogBinding(String interactionId) {
    String id = interactionId == null ? "" : interactionId.trim();
    List<PlanValidationFinding> evidence =
        id.isEmpty()
            ? List.of()
            : List.of(
                new PlanValidationFinding(RecoveryCauseCode.CATALOG_RESOLUTION.name(), id, true));
    return new RecoveryCause(
        RecoveryCauseCode.CATALOG_RESOLUTION, List.copyOf(evidence), MISSING_CATALOG_BINDING_FACT);
  }

  public boolean isMissingCatalogBinding() {
    return causeCode == RecoveryCauseCode.CATALOG_RESOLUTION
        && MISSING_CATALOG_BINDING_FACT.equals(requestedFact);
  }

  /**
   * Catalog resolution failed because a stored hint and the semantic occurrence disagree. {@code
   * semanticInteractionId} is the occurrence execution looked up, not a catalog display name.
   */
  public static RecoveryCause bindingIdentityMismatch(String semanticInteractionId) {
    String id = semanticInteractionId == null ? "" : semanticInteractionId.trim();
    List<PlanValidationFinding> evidence =
        id.isEmpty()
            ? List.of()
            : List.of(
                new PlanValidationFinding(RecoveryCauseCode.CATALOG_RESOLUTION.name(), id, true));
    return new RecoveryCause(
        RecoveryCauseCode.CATALOG_RESOLUTION,
        List.copyOf(evidence),
        BINDING_IDENTITY_MISMATCH_FACT);
  }

  public boolean isBindingIdentityMismatch() {
    return causeCode == RecoveryCauseCode.CATALOG_RESOLUTION
        && BINDING_IDENTITY_MISMATCH_FACT.equals(requestedFact);
  }

  /** True when the halt is the typed missing-brief-facts defect. */
  public boolean isMissingBriefFacts() {
    return causeCode == RecoveryCauseCode.MISSING_BRIEF_FACTS;
  }

  /** Occurrence the missing-hint cause named, when present. */
  public Optional<String> unresolvedInteractionId() {
    if (!isMissingCatalogBinding()) {
      return Optional.empty();
    }
    for (PlanValidationFinding finding : findings) {
      if (finding != null && finding.message() != null && !finding.message().isBlank()) {
        return Optional.of(finding.message());
      }
    }
    return Optional.empty();
  }

  /**
   * Mapping rules failed a known contract. Finding codes name the specific violation; this
   * aggregate cause routes to the brief producer.
   */
  public static RecoveryCause mappingContract(List<PlanValidationFinding> findings) {
    return new RecoveryCause(
        RecoveryCauseCode.MAPPING_CONTRACT, findings == null ? List.of() : findings, "");
  }

  public static RecoveryCause missingBriefFacts(List<String> missingFacts) {
    List<PlanValidationFinding> evidence = new ArrayList<>();
    if (missingFacts != null) {
      for (String fact : missingFacts) {
        if (fact != null && !fact.isBlank()) {
          evidence.add(
              new PlanValidationFinding(RecoveryCauseCode.MISSING_BRIEF_FACTS.name(), fact, true));
        }
      }
    }
    return new RecoveryCause(
        RecoveryCauseCode.MISSING_BRIEF_FACTS, List.copyOf(evidence), "");
  }

  /**
   * Rebuilds a cause from formatted {@code code: message} lines. Used by callers that still pass
   * narrative findings rather than {@link RecoveryCause}. Does not search finding messages.
   */
  public static RecoveryCause fromFormattedFindingCodes(
      String findings, StageOutcomeClass outcomeClass) {
    List<PlanValidationFinding> parsed = new ArrayList<>();
    if (findings != null && !findings.isBlank()) {
      for (String line : findings.split("\\R")) {
        if (line == null || line.isBlank()) {
          continue;
        }
        int colon = line.indexOf(':');
        String code = (colon < 0 ? line : line.substring(0, colon)).trim();
        String message = colon < 0 ? "" : line.substring(colon + 1).trim();
        if (!code.isBlank()) {
          parsed.add(new PlanValidationFinding(code, message, true));
        }
      }
    }
    return fromFindings(parsed, outcomeClass);
  }

  /**
   * Derives a cause from typed finding codes and the outcome class. Does not read finding messages
   * or exception text.
   */
  public static RecoveryCause fromHalt(
      StageOutcomeClass outcomeClass, List<ArtifactCandidate> candidates) {
    List<PlanValidationFinding> extracted = findingsOf(candidates);
    RecoveryCauseCode fromFindings = firstTypedCode(extracted);
    if (fromFindings != null) {
      return new RecoveryCause(fromFindings, extracted, "");
    }
    return new RecoveryCause(fromOutcomeClass(outcomeClass), extracted, "");
  }

  public static RecoveryCause fromFindings(
      List<PlanValidationFinding> findings, StageOutcomeClass outcomeClass) {
    List<PlanValidationFinding> list = findings == null ? List.of() : List.copyOf(findings);
    RecoveryCauseCode fromFindings = firstTypedCode(list);
    if (fromFindings != null) {
      return new RecoveryCause(fromFindings, list, "");
    }
    return new RecoveryCause(fromOutcomeClass(outcomeClass), list, "");
  }

  /**
   * Maps a finding {@code code} onto a cause. {@code null} when the code is not one this table
   * knows. Does not inspect the finding message.
   */
  public static RecoveryCauseCode fromFindingCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String normalized = code.trim();
    if (normalized.toLowerCase(Locale.ROOT).startsWith("security-")) {
      return RecoveryCauseCode.SECURITY_POLICY;
    }
    return switch (normalized) {
      case "UNKNOWN_PROPERTY", "else.condition", "else.priority" ->
          RecoveryCauseCode.UNKNOWN_PROPERTY;
      case "MISSING_REQUIRED_PROPERTY" -> RecoveryCauseCode.MISSING_REQUIRED_PROPERTY;
      case "MISSING_BRIEF_FACTS" -> RecoveryCauseCode.MISSING_BRIEF_FACTS;
      case "CATALOG_RESOLUTION" -> RecoveryCauseCode.CATALOG_RESOLUTION;
      case "MAPPING_CONTRACT",
              "MAPPING_UNKNOWN_TARGET",
              "MAPPING_MISSING_REQUIRED_TARGET",
              "MAPPING_INVALID_SOURCE",
              "MAPPING_UNSUPPORTED_EXPRESSION",
              "MAPPING_UNRESOLVED_RULE" ->
          RecoveryCauseCode.MAPPING_CONTRACT;
      default -> null;
    };
  }

  public static RecoveryCauseCode fromOutcomeClass(StageOutcomeClass outcomeClass) {
    if (outcomeClass == null) {
      return RecoveryCauseCode.VALIDATION_BLOCKER;
    }
    return switch (outcomeClass) {
      case INTERNAL_FAILURE -> RecoveryCauseCode.INTERNAL;
      case CONTRACT_FAILURE -> RecoveryCauseCode.CONTRACT_SHAPE;
      case POLICY_FAILURE -> RecoveryCauseCode.POLICY_FAILURE;
      case MISSING_MANDATORY_INPUT -> RecoveryCauseCode.MISSING_MANDATORY_INPUT;
      case RETRYABLE_TECHNICAL_FAILURE -> RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED;
      case DOMAIN_FAILURE -> RecoveryCauseCode.DOMAIN_FAILURE;
      case VALIDATION_FAILURE, NEEDS_INPUT, CANDIDATE, SUCCEEDED ->
          RecoveryCauseCode.VALIDATION_BLOCKER;
    };
  }

  private static RecoveryCauseCode firstTypedCode(List<PlanValidationFinding> findings) {
    for (PlanValidationFinding finding : findings) {
      if (finding == null) {
        continue;
      }
      RecoveryCauseCode mapped = fromFindingCode(finding.code());
      if (mapped != null) {
        return mapped;
      }
    }
    return null;
  }

  private static List<PlanValidationFinding> findingsOf(List<ArtifactCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<PlanValidationFinding> extracted = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      if (candidate == null || !(candidate.payload() instanceof PlanValidationResult result)) {
        continue;
      }
      if (result.findings() == null) {
        continue;
      }
      for (PlanValidationFinding finding : result.findings()) {
        if (finding != null) {
          extracted.add(finding);
        }
      }
    }
    return List.copyOf(extracted);
  }
}
