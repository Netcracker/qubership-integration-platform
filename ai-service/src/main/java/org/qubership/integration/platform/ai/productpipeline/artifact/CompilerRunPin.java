package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;

/** Pins the compiler package and resolved DAG for one durable product-pipeline run. */
public record CompilerRunPin(
    String compilerPackageId,
    String compilerPackageVersion,
    String compilerPackageDigest,
    int pipelineIndexSchemaVersion,
    String pipelineIndexVersion,
    String pipelineIndexDigest,
    ResolvedCompilerDag resolvedDag,
    List<String> capabilityClosure,
    Map<String, String> skillSha256ById,
    Map<String, String> addonSha256ById,
    List<ArtifactTypeRef> runtimeArtifactSchemas) {

  public CompilerRunPin {
    capabilityClosure = capabilityClosure == null ? List.of() : List.copyOf(capabilityClosure);
    skillSha256ById = skillSha256ById == null ? Map.of() : Map.copyOf(skillSha256ById);
    addonSha256ById = addonSha256ById == null ? Map.of() : Map.copyOf(addonSha256ById);
    runtimeArtifactSchemas =
        runtimeArtifactSchemas == null ? List.of() : List.copyOf(runtimeArtifactSchemas);
  }
}
