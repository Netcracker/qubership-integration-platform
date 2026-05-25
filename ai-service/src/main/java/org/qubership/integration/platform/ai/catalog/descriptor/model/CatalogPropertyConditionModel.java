package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mirrors runtime-catalog {@code PropertyValidation.PropertyCondition} for YAML deserialization.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogPropertyConditionModel {

  private String property;
  private String equalTo;
  private List<String> mandatoryProperties = new ArrayList<>();

  public void setMandatoryProperties(List<String> mandatoryProperties) {
    this.mandatoryProperties =
        mandatoryProperties != null ? mandatoryProperties : new ArrayList<>();
  }

  /**
   * Same semantics as runtime-catalog: when {@code property} equals {@code equalTo} (ignore case),
   * every {@code mandatoryProperties} entry must be non-empty.
   */
  public boolean evaluate(Map<String, Object> properties) {
    if (mandatoryProperties == null || mandatoryProperties.isEmpty()) {
      return true;
    }
    String actual = Objects.toString(properties.get(property), null);
    if (!CatalogDescriptorValuePresence.equalsIgnoreCase(actual, equalTo)) {
      return true;
    }
    for (String propertyName : mandatoryProperties) {
      if (!CatalogDescriptorValuePresence.isPresent(properties.get(propertyName))) {
        return false;
      }
    }
    return true;
  }
}
