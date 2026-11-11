package org.qubership.integration.platform.ai.productpipeline.packageindex;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;

/** Canonical capability manifest loaded from product-pipelines YAML. */
public record CapabilityManifest(
    int schemaVersion,
    String capabilityId,
    String capabilityVersion,
    List<ArtifactTypeRef> consumes,
    List<ArtifactTypeRef> produces,
    List<String> requiredSkills,
    String dynamicSkillSet,
    List<String> requiredAddons,
    List<String> requiredRules) {

  public CapabilityManifest {
    consumes = consumes == null ? List.of() : List.copyOf(consumes);
    produces = produces == null ? List.of() : List.copyOf(produces);
    requiredSkills =
        requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
    requiredAddons =
        requiredAddons == null ? List.of() : List.copyOf(requiredAddons);
    requiredRules =
        requiredRules == null ? List.of() : List.copyOf(requiredRules);
  }
}
