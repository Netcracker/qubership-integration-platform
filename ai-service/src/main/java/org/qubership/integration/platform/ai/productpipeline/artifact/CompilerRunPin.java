package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;

/** Pins the compiler package, resolved DAG, and approved semantic revision for one run. */
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
    List<ArtifactTypeRef> runtimeArtifactSchemas,
    String subjectArtifactKind,
    String subjectSchemaVersion,
    String subjectRevisionId,
    String subjectSha256,
    String compilerContractVersion,
    String compilerContractSha256) {

  public CompilerRunPin {
    capabilityClosure = capabilityClosure == null ? List.of() : List.copyOf(capabilityClosure);
    skillSha256ById = skillSha256ById == null ? Map.of() : Map.copyOf(skillSha256ById);
    addonSha256ById = addonSha256ById == null ? Map.of() : Map.copyOf(addonSha256ById);
    runtimeArtifactSchemas =
        runtimeArtifactSchemas == null ? List.of() : List.copyOf(runtimeArtifactSchemas);
  }

  /**
   * Copies this pin's DAG closure and overlays the semantic subject from {@code semanticPin}.
   * Create-chain runs already hold a DAG pin; T06 semantic resolve fills only subject fields.
   */
  public CompilerRunPin withSemanticSubject(CompilerRunPin semanticPin) {
    if (semanticPin == null) {
      throw new IllegalArgumentException("semanticPin is required");
    }
    return new CompilerRunPin(
        compilerPackageId,
        compilerPackageVersion,
        compilerPackageDigest,
        pipelineIndexSchemaVersion,
        pipelineIndexVersion,
        pipelineIndexDigest,
        resolvedDag,
        capabilityClosure,
        skillSha256ById,
        addonSha256ById,
        runtimeArtifactSchemas,
        semanticPin.subjectArtifactKind(),
        semanticPin.subjectSchemaVersion(),
        semanticPin.subjectRevisionId(),
        semanticPin.subjectSha256(),
        semanticPin.compilerContractVersion(),
        semanticPin.compilerContractSha256());
  }
}
