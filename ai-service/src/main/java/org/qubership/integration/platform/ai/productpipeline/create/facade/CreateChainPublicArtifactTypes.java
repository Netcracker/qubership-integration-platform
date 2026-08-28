package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Locale;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;

/**
 * Maps public create-chain artifact type names to and from compiler {@link Kind} values.
 *
 * <p>Public names follow the A2A artifact allowlist. Profile wire names such as {@code
 * ids-document} resolve to the same public {@code integration-design} type.
 */
public final class CreateChainPublicArtifactTypes {

  public static final String REQUIREMENT_DRAFT = "requirement-draft";
  public static final String REQUIREMENT_BRIEF = "requirement-brief";
  public static final String INTEGRATION_DESIGN = "integration-design";
  public static final String IMPLEMENTATION_PLAN = "implementation-plan";
  public static final String VALIDATION_REPORT = "validation-report";
  public static final String MATERIALIZATION_RESULT = "materialization-result";
  public static final String FAILURE_REPORT = "failure-report";

  private CreateChainPublicArtifactTypes() {}

  public static Optional<String> toPublicType(Kind kind) {
    if (kind == null) {
      return Optional.empty();
    }
    return switch (kind) {
      case REQUIREMENT_DRAFT -> Optional.of(REQUIREMENT_DRAFT);
      case REQUIREMENT_BRIEF -> Optional.of(REQUIREMENT_BRIEF);
      case IDS_DOCUMENT -> Optional.of(INTEGRATION_DESIGN);
      case IMPLEMENTATION_PLAN -> Optional.of(IMPLEMENTATION_PLAN);
      case PLAN_VALIDATION_RESULT, COMPILER_VALIDATION_BUNDLE, EXECUTOR_VALIDATION_BUNDLE ->
          Optional.of(VALIDATION_REPORT);
      case MATERIALIZATION_RESULT -> Optional.of(MATERIALIZATION_RESULT);
      case FAILURE_RECORD -> Optional.of(FAILURE_REPORT);
      default -> Optional.empty();
    };
  }

  public static Optional<Kind> toKind(String publicOrWireType) {
    if (publicOrWireType == null || publicOrWireType.isBlank()) {
      return Optional.empty();
    }
    String normalized = publicOrWireType.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case REQUIREMENT_DRAFT -> Optional.of(Kind.REQUIREMENT_DRAFT);
      case REQUIREMENT_BRIEF -> Optional.of(Kind.REQUIREMENT_BRIEF);
      case INTEGRATION_DESIGN, "ids-document" -> Optional.of(Kind.IDS_DOCUMENT);
      case IMPLEMENTATION_PLAN -> Optional.of(Kind.IMPLEMENTATION_PLAN);
      case VALIDATION_REPORT, "plan-validation-result" -> Optional.of(Kind.PLAN_VALIDATION_RESULT);
      case MATERIALIZATION_RESULT -> Optional.of(Kind.MATERIALIZATION_RESULT);
      case FAILURE_REPORT, "failure-record" -> Optional.of(Kind.FAILURE_RECORD);
      default -> fromWireName(normalized);
    };
  }

  /**
   * Inverse of {@link #toApprovalType} for a kind with no public name. Approval asks for the wire
   * name of such a kind, so refusing to read it back rejected the caller's own echo: an approval
   * of {@code chain-semantic-revision} failed as WrongArtifactType against itself.
   */
  private static Optional<Kind> fromWireName(String normalized) {
    for (Kind kind : Kind.values()) {
      if (toPublicType(kind).isEmpty() && wireName(kind).equals(normalized)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  /** Public type for approval waits, including requirement-brief when that stage waits. */
  public static String toApprovalType(Kind kind) {
    return toPublicType(kind).orElseGet(() -> wireName(kind));
  }

  private static String wireName(Kind kind) {
    return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
