package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Subset of runtime-catalog {@code ElementProperty} fields needed for PATCH validation. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogDescriptorPropertyModel {

  private String name;
  private String type = "string";
  private boolean mandatory;
  private List<String> allowedValues = new ArrayList<>();
  @JsonProperty("default")
  private Object defaultValue;
  private String mask;
  private boolean multiple;
  private CatalogPropertyValidationModel validation;

  public void setAllowedValues(List<String> allowedValues) {
    this.allowedValues = allowedValues != null ? allowedValues : new ArrayList<>();
  }

  public boolean isCustomType() {
    return "custom".equalsIgnoreCase(type);
  }
}
