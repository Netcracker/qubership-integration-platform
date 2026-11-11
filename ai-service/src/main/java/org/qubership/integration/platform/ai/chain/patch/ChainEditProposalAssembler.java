package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchShapeValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

/**
 * Turns a compiled net patch into something the writer can take, or says why it cannot.
 *
 * <p>The interactive scenario and the regression harness differ in one thing: whether a reader
 * answers a card before the write. Everything before that — closure expansion, ownership, shape,
 * and the semantic check — has to be identical, or a change the harness passes can still fail in
 * front of a reader.
 */
@ApplicationScoped
public class ChainEditProposalAssembler {

  private final ChainPatchOwnership ownership;
  private final ValidatedGraphPatchApplier patchApplier;
  private final ChainPatchSemanticValidator semanticValidator;

  @Inject
  public ChainEditProposalAssembler(
      ChainPatchOwnership ownership,
      ValidatedGraphPatchApplier patchApplier,
      ChainPatchSemanticValidator semanticValidator) {
    this.ownership = Objects.requireNonNull(ownership, "ownership");
    this.patchApplier = Objects.requireNonNull(patchApplier, "patchApplier");
    this.semanticValidator = Objects.requireNonNull(semanticValidator, "semanticValidator");
  }

  /** Either a patch ready to write, or the reason it is not. */
  public sealed interface Assembled {
    record Ready(GraphPatch patch, PatchedChain patched) implements Assembled {}

    record Refused(String message, ChainPatchRefusalKind kind) implements Assembled {}
  }

  /** Why an assembly stopped, for callers that report refusals by category. */
  public enum ChainPatchRefusalKind {
    STRUCTURAL,
    OWNERSHIP,
    SEMANTIC
  }

  public Assembled assemble(
      ImportedChainPlan imported, String chainId, GraphPatch proposed, boolean mayRemove) {
    List<String> shapeErrors = GraphPatchShapeValidator.validate(proposed);
    if (!shapeErrors.isEmpty()) {
      return new Assembled.Refused(
          "The change could not be read: " + GraphPatchShapeValidator.summarize(shapeErrors),
          ChainPatchRefusalKind.STRUCTURAL);
    }

    // Grown to include everything the catalog will cascade, so the card, the write and the digest
    // all describe the same change.
    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(imported.graph(), proposed);
    if (!expansion.coherent()) {
      return new Assembled.Refused(
          "The change contradicts itself: " + String.join("; ", expansion.conflicts()),
          ChainPatchRefusalKind.STRUCTURAL);
    }
    GraphPatch patch = expansion.patch();

    GraphPatchApplyResult applied =
        patchApplier.apply(
            ChainPatchPipeline.executionContext(imported, chainId, patch, ownership, mayRemove),
            patch);
    if (!applied.applied()) {
      String summary = applied.validationResult().summary();
      boolean ownershipViolation = ChainPatchPipeline.isOwnershipViolation(applied);
      return new Assembled.Refused(
          ownershipViolation
              ? "That change is outside what I may edit here: " + summary
              : "The change could not be applied: " + summary,
          ownershipViolation
              ? ChainPatchRefusalKind.OWNERSHIP
              : ChainPatchRefusalKind.STRUCTURAL);
    }

    // Asked before the card, not after it: a card for a change already known to be refused costs
    // the reader an answer that can only be thrown away.
    List<String> introduced =
        semanticValidator.introducedProblems(imported.graph(), applied.graph(), patch);
    if (!introduced.isEmpty()) {
      return new Assembled.Refused(
          "That change would leave the chain broken: " + String.join("; ", introduced),
          ChainPatchRefusalKind.SEMANTIC);
    }

    return new Assembled.Ready(
        patch,
        new PatchedChain(imported.graph(), applied.graph(), imported.materializationMap()));
  }
}
