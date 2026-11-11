package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;

/** Pins every runtime, knowledge, and compiler-run choice for one durable product-pipeline run. */
public record RunManifest(
    String runId,
    String parentRunId,
    List<CompilationArtifacts.Reference> sourceReferences,
    String runtimeSelection,
    String profileId,
    String profileVersion,
    String profileDigest,
    String referenceBaselineId,
    String referenceBaselineDigest,
    List<DependencyClosureEntry> dependencyClosure,
    String dependencyClosureDigest,
    KnowledgePackageRef knowledgePackage,
    String languageVersion,
    List<ArtifactTypeRef> artifactSchemaVersions,
    CompilerRunPin compilerRunPin,
    String responseLocale) {

  public RunManifest {
    sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
    dependencyClosure = dependencyClosure == null ? List.of() : List.copyOf(dependencyClosure);
    artifactSchemaVersions =
        artifactSchemaVersions == null ? List.of() : List.copyOf(artifactSchemaVersions);
    responseLocale =
        responseLocale == null || responseLocale.isBlank() ? "en" : responseLocale.strip();
  }

  /** Compatibility constructor for manifests created before response locale pinning. */
  public RunManifest(
      String runId,
      String parentRunId,
      List<CompilationArtifacts.Reference> sourceReferences,
      String runtimeSelection,
      String profileId,
      String profileVersion,
      String profileDigest,
      String referenceBaselineId,
      String referenceBaselineDigest,
      List<DependencyClosureEntry> dependencyClosure,
      String dependencyClosureDigest,
      KnowledgePackageRef knowledgePackage,
      String languageVersion,
      List<ArtifactTypeRef> artifactSchemaVersions,
      CompilerRunPin compilerRunPin) {
    this(
        runId,
        parentRunId,
        sourceReferences,
        runtimeSelection,
        profileId,
        profileVersion,
        profileDigest,
        referenceBaselineId,
        referenceBaselineDigest,
        dependencyClosure,
        dependencyClosureDigest,
        knowledgePackage,
        languageVersion,
        artifactSchemaVersions,
        compilerRunPin,
        "en");
  }
}
