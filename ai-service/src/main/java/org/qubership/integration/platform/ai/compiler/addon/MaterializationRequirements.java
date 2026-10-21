package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Catalog materialization overlay loaded from addon global data documents. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterializationRequirements(
    int version, Map<String, ElementRequirement> elementRequirements) {

  public MaterializationRequirements {
    elementRequirements =
        elementRequirements != null ? Map.copyOf(elementRequirements) : Map.of();
  }

  public static MaterializationRequirements empty() {
    return new MaterializationRequirements(1, Map.of());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ElementRequirement(
      String ownerGenerator,
      List<String> requiredProperties,
      Map<String, String> examples) {

    public ElementRequirement {
      requiredProperties =
          requiredProperties != null ? List.copyOf(requiredProperties) : List.of();
      examples = examples != null ? Map.copyOf(examples) : Map.of();
    }
  }
}
