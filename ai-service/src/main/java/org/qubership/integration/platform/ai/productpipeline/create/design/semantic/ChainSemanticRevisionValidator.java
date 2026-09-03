package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Fail-closed contract check for a full semantic revision before planning. */
public interface ChainSemanticRevisionValidator {

  void validate(ChainSemanticRevision revision, CompilerContract contract);

  default void validate(
      ChainSemanticRevision revision, CompilerContract contract, RequirementBrief brief) {
    validate(revision, contract);
  }
}
