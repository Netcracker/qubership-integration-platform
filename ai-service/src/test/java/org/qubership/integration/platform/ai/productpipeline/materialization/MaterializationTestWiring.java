package org.qubership.integration.platform.ai.productpipeline.materialization;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator;

public final class MaterializationTestWiring {
  private MaterializationTestWiring() {}

  public static MaterializationCapability withCopyValidation(
      MaterializationCapability capability, CompilationArtifacts artifacts) {
    capability.copyMappings = new CopyMappingMaterializationValidator(artifacts);
    return capability;
  }
}
