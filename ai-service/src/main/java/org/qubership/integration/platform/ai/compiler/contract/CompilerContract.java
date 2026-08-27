package org.qubership.integration.platform.ai.compiler.contract;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned create-chain support matrix. Validators and generators read these values instead of
 * defining a second copy.
 */
public record CompilerContract(
    String contractVersion,
    String semanticSchemaVersion,
    Map<String, ElementContract> elements,
    Map<String, TopologyContract> topology,
    Set<String> requiredArtifacts,
    Set<String> requiredAddons,
    Set<String> requiredKnowledgeFragments,
    String sha256) {

  public static final String V1 = "create-chain-compiler-contract/v1";

  public CompilerContract {
    elements = copyElements(elements);
    topology = copyTopology(topology);
    requiredArtifacts = copyIdentifiers(requiredArtifacts);
    requiredAddons = copyIdentifiers(requiredAddons);
    requiredKnowledgeFragments = copyIdentifiers(requiredKnowledgeFragments);
  }

  private static Map<String, ElementContract> copyElements(Map<String, ElementContract> elements) {
    return elements != null ? Map.copyOf(new LinkedHashMap<>(elements)) : Map.of();
  }

  private static Map<String, TopologyContract> copyTopology(
      Map<String, TopologyContract> topology) {
    return topology != null ? Map.copyOf(new LinkedHashMap<>(topology)) : Map.of();
  }

  private static Set<String> copyIdentifiers(Set<String> identifiers) {
    return identifiers != null ? Set.copyOf(new LinkedHashSet<>(identifiers)) : Set.of();
  }

  /** Allowed containment, required properties, and runtime descriptor constraints for one type. */
  public record ElementContract(
      Map<String, ContainmentRole> containmentRoles,
      List<String> requiredProperties,
      String materializationRuleId,
      RuntimeDescriptorConstraints runtimeDescriptor) {

    public ElementContract {
      containmentRoles =
          containmentRoles != null ? Map.copyOf(new LinkedHashMap<>(containmentRoles)) : Map.of();
      requiredProperties = requiredProperties != null ? List.copyOf(requiredProperties) : List.of();
    }
  }

  /** Cardinality of one child role. A null {@code max} means unbounded. */
  public record ContainmentRole(int min, Integer max) {}

  /** Runtime catalog constraints that validators compare to descriptors. */
  public record RuntimeDescriptorConstraints(
      String type, boolean container, Integer minimumChildren, boolean deprecated) {}

  /** Topology support and cardinalities for one construct. Omitted {@code supported} is true. */
  public record TopologyContract(
      Boolean supported, Integer minimumBranches, String reconvergence) {

    public TopologyContract {
      supported = supported == null || supported;
    }
  }
}
