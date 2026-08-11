package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.List;

/** One skill entry in the compiled BUILD_CHAIN pipeline index. */
public record CompilerPipelineEntry(
    String skillId,
    String category,
    String compilerStage,
    int order,
    String generatorId,
    boolean generationCandidate,
    String sourcePath,
    String sourceSha256,
    String confidence,
    List<String> notes,
    List<String> consumes,
    List<String> produces) {}
