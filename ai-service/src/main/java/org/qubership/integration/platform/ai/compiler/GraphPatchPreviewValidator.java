package org.qubership.integration.platform.ai.compiler;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/**
 * Shared capture/harvest preview: ownership apply, structural validation, and readiness gaps.
 * Harvest must always re-run this validator; do not reuse a stored preview result.
 */
public final class GraphPatchPreviewValidator {

  private final ValidatedGraphPatchApplier validatedPatchApplier;
  private final GraphPatchApplier patchApplier;
  private final ChainPlanGraphValidator graphValidator;
  private final GeneratorReadinessEvaluator readinessEvaluator;
  private final CanonicalGraphDigest canonicalGraphDigest;

  public GraphPatchPreviewValidator(
      ValidatedGraphPatchApplier validatedPatchApplier,
      GraphPatchApplier patchApplier,
      ChainPlanGraphValidator graphValidator,
      GeneratorReadinessEvaluator readinessEvaluator,
      CanonicalGraphDigest canonicalGraphDigest) {
    this.validatedPatchApplier =
        Objects.requireNonNull(validatedPatchApplier, "validatedPatchApplier");
    this.patchApplier = Objects.requireNonNull(patchApplier, "patchApplier");
    this.graphValidator = Objects.requireNonNull(graphValidator, "graphValidator");
    this.readinessEvaluator = Objects.requireNonNull(readinessEvaluator, "readinessEvaluator");
    this.canonicalGraphDigest =
        Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
  }

  public GraphPatchPreviewResult validate(
      ChainPlanGraph pinnedBaseGraph,
      GraphPatch patch,
      GraphPatchExecutionContext executionContext,
      List<String> readinessSignals) {
    Objects.requireNonNull(pinnedBaseGraph, "pinnedBaseGraph");
    Objects.requireNonNull(patch, "patch");
    String inputGraphDigest = canonicalGraphDigest.sha256(pinnedBaseGraph);
    if (digestMismatch(executionContext, inputGraphDigest)) {
      return new GraphPatchPreviewResult(
          pinnedBaseGraph,
          new ValidationResult(true, List.of(), "ok"),
          pinnedBaseGraph,
          List.of(),
          List.of(),
          false,
          inputGraphDigest);
    }
    GraphPatchApplyResult applied =
        executionContext != null
            ? validatedPatchApplier.apply(executionContext, patch)
            : patchApplier.apply(pinnedBaseGraph, patch);
    ValidationResult ownershipResult = applied.validationResult();
    if (!ownershipResult.valid()) {
      return new GraphPatchPreviewResult(
          pinnedBaseGraph,
          ownershipResult,
          pinnedBaseGraph,
          List.of(),
          List.of(),
          false,
          inputGraphDigest);
    }
    ChainPlanGraph patched = applied.graph();
    List<String> baseStructural = List.copyOf(graphValidator.validate(pinnedBaseGraph));
    List<String> patchedStructural = List.copyOf(graphValidator.validate(patched));
    List<String> structural =
        patchedStructural.stream().filter(error -> !baseStructural.contains(error)).toList();
    List<String> declared = readinessSignals == null ? List.of() : List.copyOf(readinessSignals);
    List<String> readinessGaps =
        declared.isEmpty()
            ? List.of()
            : List.copyOf(readinessEvaluator.unmetCompleteness(declared, patched));
    boolean pass = structural.isEmpty() && readinessGaps.isEmpty();
    return new GraphPatchPreviewResult(
        pinnedBaseGraph,
        ownershipResult,
        patched,
        structural,
        readinessGaps,
        pass,
        inputGraphDigest);
  }

  /**
   * Fail closed when the execution context declares a non-blank input digest that does not match
   * the canonical digest of the resolved pinned base graph.
   */
  static boolean digestMismatch(GraphPatchExecutionContext executionContext, String inputGraphDigest) {
    return executionContext != null
        && executionContext.inputGraphDigest() != null
        && !executionContext.inputGraphDigest().isBlank()
        && !Objects.equals(inputGraphDigest, executionContext.inputGraphDigest());
  }

  public record GraphPatchPreviewResult(
      ChainPlanGraph resolvedPinnedBaseGraph,
      ValidationResult ownershipResult,
      ChainPlanGraph patchedGraph,
      List<String> structuralValidation,
      List<String> readinessGaps,
      boolean pass,
      String inputGraphDigest) {

    public GraphPatchPreviewResult {
      structuralValidation =
          structuralValidation == null ? List.of() : List.copyOf(structuralValidation);
      readinessGaps = readinessGaps == null ? List.of() : List.copyOf(readinessGaps);
    }
  }
}
