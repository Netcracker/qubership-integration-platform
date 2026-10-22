package org.qubership.integration.platform.ai.compiler.contract;

/** Returns one pinned compiler contract revision. Unknown versions fail closed. */
public interface CompilerContractRepository {

  CompilerContract require(String contractVersion);
}
