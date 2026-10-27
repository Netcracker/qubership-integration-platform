package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.List;
import java.util.Set;

/**
 * Chooses the next requirement recovery step for a repeated candidate.
 *
 * <p>A rejected candidate does not create authority. Only a source-backed semantic baseline and an
 * already accepted draft protect meaning. Identity conflicts and unbound literals are never author
 * questions.
 */
public final class RequirementRecoveryLadder {

  /** Recovery step stored with the capture feedback. */
  public enum Stage {
    FOCUSED_REPAIR,
    PROJECTION_REBUILD,
    ASK_AUTHOR,
    INTERNAL_FAILURE
  }

  /** How one finding code participates in the next step. */
  public enum FindingClass {
    REPAIRABLE_REPRESENTATION,
    SEMANTIC_AMBIGUITY,
    IDENTITY_CONFLICT,
    UNBOUND_LITERAL,
    OTHER
  }

  private static final Set<String> REPRESENTATION = Set.of(
      "INVALID_ELEMENT_INTENT",
      "INVALID_ELEMENT_OWNER",
      "INVALID_ELEMENT_REFERENCE",
      "INVALID_ELEMENT_PARENT",
      "INVALID_HOOK_INTENT",
      "INVALID_CHAIN_INTENT",
      "ELEMENT_FLOW_ORDER_MISMATCH",
      "UNKNOWN_ELEMENT_REFERENCE",
      "UNKNOWN_INTERACTION_REFERENCE",
      "INVALID_REQUESTED_PROPERTY",
      "CAPABILITY_ANCHOR_CONFLICT",
      "DUPLICATE_ENTITY",
      "FLOW_CYCLE",
      "SELF_TRANSITION",
      "INBOUND_HAS_PREDECESSOR",
      "DUPLICATE_TRANSITION",
      "UNSUPPORTED_COMPILER_ELEMENT_TYPE",
      "UNKNOWN_ELEMENT_TYPE",
      "MISSING_ANCHOR_INTENT",
      "NATIVE_CAPABILITY_REQUIRED",
      "CAPABILITY_DIRECTION_MISMATCH",
      "CAPABILITY_FIELD_NOT_APPLICABLE",
      "UNSUPPORTED_CAPABILITY",
      "INVALID_VALUE");

  private static final Set<String> AMBIGUITY = Set.of(
      "SEMANTIC_AMBIGUITY", "AMBIGUOUS_TARGET_FOLDER", "POLICY_RESPONSE_REQUIRED");

  private static final Set<String> IGNORED = Set.of("UNCHANGED_PROPOSAL", "REPAIR_NO_PROGRESS");

  private RequirementRecoveryLadder() {}

  /** Classifies one issue code. Marker codes and unknown codes are {@link FindingClass#OTHER}. */
  public static FindingClass classify(String code) {
    if (code == null || code.isBlank() || IGNORED.contains(code)) {
      return FindingClass.OTHER;
    }
    if ("UNBOUND_REQUIRED_LITERAL".equals(code)) {
      return FindingClass.UNBOUND_LITERAL;
    }
    if ("CONFLICTING_ELEMENT_INTENT".equals(code)) {
      return FindingClass.IDENTITY_CONFLICT;
    }
    if (AMBIGUITY.contains(code)) {
      return FindingClass.SEMANTIC_AMBIGUITY;
    }
    if (REPRESENTATION.contains(code) || code.startsWith("MAPPING_") || code.startsWith("ELEMENT_LITERAL_")
        || code.startsWith("BODY_")) {
      return FindingClass.REPAIRABLE_REPRESENTATION;
    }
    return FindingClass.OTHER;
  }

  /** True when any counted finding can be repaired by changing the projection. */
  public static boolean repairableRepresentation(List<String> codes) {
    return relevant(codes).anyMatch(code -> {
      var kind = classify(code);
      return kind == FindingClass.REPAIRABLE_REPRESENTATION || kind == FindingClass.IDENTITY_CONFLICT;
    });
  }

  /**
   * True only when every counted finding is semantic ambiguity. An identity conflict or an unbound
   * literal keeps this false.
   */
  public static boolean asksAuthor(List<String> codes) {
    var counted = relevant(codes).toList();
    return !counted.isEmpty() && counted.stream().allMatch(code -> classify(code) == FindingClass.SEMANTIC_AMBIGUITY);
  }

  /**
   * Advances a repeated candidate. A repairable representation or semantic ambiguity leaves focused
   * repair for a projection rebuild. The next repeat asks the author only for semantic ambiguity.
   * A repeated representation after that rebuild is an internal failure. Other findings stay put.
   */
  public static Stage advance(Stage current, List<String> codes, boolean repeatedCandidate) {
    var stage = current == null ? Stage.FOCUSED_REPAIR : current;
    if (!repeatedCandidate) {
      return stage;
    }
    if (stage == Stage.FOCUSED_REPAIR && (repairableRepresentation(codes) || asksAuthor(codes))) {
      return Stage.PROJECTION_REBUILD;
    }
    if (stage == Stage.PROJECTION_REBUILD && asksAuthor(codes)) {
      return Stage.ASK_AUTHOR;
    }
    if (stage == Stage.PROJECTION_REBUILD && repairableRepresentation(codes)) {
      return Stage.INTERNAL_FAILURE;
    }
    return stage;
  }

  private static java.util.stream.Stream<String> relevant(List<String> codes) {
    if (codes == null) {
      return java.util.stream.Stream.empty();
    }
    return codes.stream().filter(code -> code != null && !code.isBlank() && !IGNORED.contains(code));
  }
}
