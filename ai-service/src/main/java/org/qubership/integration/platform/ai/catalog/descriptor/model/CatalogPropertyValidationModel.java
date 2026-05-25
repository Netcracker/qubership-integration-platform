package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Mirrors runtime-catalog {@code PropertyValidation} semantics. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogPropertyValidationModel {

  private List<String> anyOf = new ArrayList<>();
  private List<String> allOf = new ArrayList<>();
  private List<CatalogPropertyConditionModel> conditions = new ArrayList<>();

  public void setAnyOf(List<String> anyOf) {
    this.anyOf = anyOf != null ? anyOf : new ArrayList<>();
  }

  public void setAllOf(List<String> allOf) {
    this.allOf = allOf != null ? allOf : new ArrayList<>();
  }

  public void setConditions(List<CatalogPropertyConditionModel> conditions) {
    this.conditions = conditions != null ? conditions : new ArrayList<>();
  }

  /**
   * Matches runtime-catalog {@code PropertyValidation.arePropertiesValid}: each non-empty block
   * overwrites {@code valid}.
   */
  public boolean arePropertiesValid(Map<String, Object> properties) {
    boolean valid = true;
    if (!anyOf.isEmpty()) {
      valid =
          anyOf.stream()
              .filter(java.util.Objects::nonNull)
              .anyMatch(name -> CatalogDescriptorValuePresence.isPresent(properties.get(name)));
    }
    if (!allOf.isEmpty()) {
      valid =
          allOf.stream()
              .filter(java.util.Objects::nonNull)
              .allMatch(name -> CatalogDescriptorValuePresence.isPresent(properties.get(name)));
    }
    if (!conditions.isEmpty()) {
      valid =
          conditions.stream()
              .filter(java.util.Objects::nonNull)
              .allMatch(c -> c.evaluate(properties));
    }
    return valid;
  }
}
