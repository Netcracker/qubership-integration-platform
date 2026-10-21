package org.qubership.integration.platform.ai.plan;

import java.util.List;

/**
 * Human-readable implementation plan captured before the compiler spine runs.
 *
 * <p>Schema version 1 stores only {@code planText} and provenance. Schema version 2 adds
 * structured decision-critical facts that the deterministic renderer must cover.
 */
public record ImplementationPlan(
    int schemaVersion,
    String planText,
    String sourceSkillId,
    String sourceSkillVersion,
    List<String> endpointFacts,
    List<String> branchFacts,
    List<String> scriptOutcomes,
    List<String> serviceBindings,
    List<String> negativeConstraints,
    List<String> skillOwnership,
    List<String> sourceArtifactReferences,
    List<String> dependencyProvenance) {

  public static final int SCHEMA_VERSION_1 = 1;
  public static final int SCHEMA_VERSION_2 = 2;

  public ImplementationPlan {
    if (schemaVersion <= 0) {
      schemaVersion = SCHEMA_VERSION_1;
    }
    planText = planText == null ? "" : planText;
    endpointFacts = endpointFacts == null ? List.of() : List.copyOf(endpointFacts);
    branchFacts = branchFacts == null ? List.of() : List.copyOf(branchFacts);
    scriptOutcomes = scriptOutcomes == null ? List.of() : List.copyOf(scriptOutcomes);
    serviceBindings = serviceBindings == null ? List.of() : List.copyOf(serviceBindings);
    negativeConstraints =
        negativeConstraints == null ? List.of() : List.copyOf(negativeConstraints);
    skillOwnership = skillOwnership == null ? List.of() : List.copyOf(skillOwnership);
    sourceArtifactReferences =
        sourceArtifactReferences == null ? List.of() : List.copyOf(sourceArtifactReferences);
    dependencyProvenance =
        dependencyProvenance == null ? List.of() : List.copyOf(dependencyProvenance);
  }

  /** Schema-version-1 compatibility constructor. */
  public ImplementationPlan(String planText, String sourceSkillId, String sourceSkillVersion) {
    this(
        SCHEMA_VERSION_1,
        planText,
        sourceSkillId,
        sourceSkillVersion,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  public ImplementationPlan(String planText) {
    this(planText, null, null);
  }

  public static ImplementationPlan schemaVersion2(
      String planText,
      String sourceSkillId,
      String sourceSkillVersion,
      List<String> endpointFacts,
      List<String> branchFacts,
      List<String> scriptOutcomes,
      List<String> serviceBindings,
      List<String> negativeConstraints,
      List<String> skillOwnership,
      List<String> sourceArtifactReferences,
      List<String> dependencyProvenance) {
    return new ImplementationPlan(
        SCHEMA_VERSION_2,
        planText,
        sourceSkillId,
        sourceSkillVersion,
        endpointFacts,
        branchFacts,
        scriptOutcomes,
        serviceBindings,
        negativeConstraints,
        skillOwnership,
        sourceArtifactReferences,
        dependencyProvenance);
  }

  public List<String> allStructuredFacts() {
    return java.util.stream.Stream.of(
            endpointFacts,
            branchFacts,
            scriptOutcomes,
            serviceBindings,
            negativeConstraints,
            skillOwnership,
            sourceArtifactReferences,
            dependencyProvenance)
        .flatMap(List::stream)
        .filter(fact -> fact != null && !fact.isBlank())
        .toList();
  }
}
