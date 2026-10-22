package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphOwnershipFact;

/**
 * Run-local ordered patch ledger accumulated during one planning {@code execute()} invocation.
 * Not an application-scoped bean.
 */
public record PlanningPatchLedger(
    List<Reference> orderedReferences, List<GraphOwnershipFact> ownershipFacts) {

  public PlanningPatchLedger {
    orderedReferences = orderedReferences == null ? List.of() : List.copyOf(orderedReferences);
    ownershipFacts = ownershipFacts == null ? List.of() : List.copyOf(ownershipFacts);
  }

  static final class Builder {
    private final List<Reference> orderedReferences = new ArrayList<>();
    private final List<GraphOwnershipFact> ownershipFacts = new ArrayList<>();

    void addApplicable(Reference reference, GraphOwnershipFact ownershipFact) {
      orderedReferences.add(reference);
      ownershipFacts.add(ownershipFact);
    }

    void addNotApplicable(Reference reference) {
      orderedReferences.add(reference);
    }

    PlanningPatchLedger build() {
      return new PlanningPatchLedger(orderedReferences, ownershipFacts);
    }
  }
}
