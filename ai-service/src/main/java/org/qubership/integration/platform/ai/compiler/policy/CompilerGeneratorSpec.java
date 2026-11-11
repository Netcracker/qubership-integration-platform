package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;

/** Generator or validator specification discovered from production compiler catalog sources. */
public record CompilerGeneratorSpec(
    String skillName,
    String generatorId,
    String compilerStage,
    String category,
    List<String> inputs,
    List<String> outputs,
    List<String> dependencies,
    List<String> generatedArtifacts,
    List<String> supportedElements,
    List<String> sourcePaths) {

  public CompilerGeneratorSpec {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    generatedArtifacts = generatedArtifacts == null ? List.of() : List.copyOf(generatedArtifacts);
    supportedElements = supportedElements == null ? List.of() : List.copyOf(supportedElements);
    sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
  }

  public boolean hasGeneratorId() {
    return generatorId != null && !generatorId.isBlank();
  }
}
