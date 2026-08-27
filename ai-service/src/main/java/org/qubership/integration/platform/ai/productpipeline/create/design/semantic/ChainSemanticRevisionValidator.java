package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;

/** Fail-closed contract check for a full semantic revision before planning. */
public interface ChainSemanticRevisionValidator {

  void validate(ChainSemanticRevision revision, CompilerContract contract);
}
