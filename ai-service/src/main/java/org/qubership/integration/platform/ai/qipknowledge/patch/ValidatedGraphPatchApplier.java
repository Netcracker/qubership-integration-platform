package org.qubership.integration.platform.ai.qipknowledge.patch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/** Applies patches only after ownership validation passes for the immutable input graph. */
@ApplicationScoped
public class ValidatedGraphPatchApplier {

  private final GraphPatchOwnershipValidator ownershipValidator;
  private final GraphPatchApplier graphPatchApplier;

  @Inject
  public ValidatedGraphPatchApplier(
      GraphPatchOwnershipValidator ownershipValidator, GraphPatchApplier graphPatchApplier) {
    this.ownershipValidator = Objects.requireNonNull(ownershipValidator, "ownershipValidator");
    this.graphPatchApplier = Objects.requireNonNull(graphPatchApplier, "graphPatchApplier");
  }

  public GraphPatchApplyResult apply(GraphPatchExecutionContext context, GraphPatch patch) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(patch, "patch");
    var ownership = ownershipValidator.validate(context, patch);
    if (!ownership.valid()) {
      return new GraphPatchApplyResult(context.inputGraph(), ownership);
    }
    return graphPatchApplier.apply(context.inputGraph(), patch);
  }
}
