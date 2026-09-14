package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Renders an approval-view IDS from a captured semantic revision. */
public interface ChainSemanticIdsRenderer {

  IdsDocument render(ChainSemanticRevision revision, CompilerContract contract);

  default IdsDocument render(
      ChainSemanticRevision revision, CompilerContract contract, RequirementBrief brief) {
    return render(revision, contract);
  }
}
