package org.qubership.integration.platform.ai.compiler.pipeline;

/** Source checksums used to build the compiler pipeline index. */
public record CompilerPipelineIndexSource(String generatorContractsSha, String ruleMappingSha) {}
