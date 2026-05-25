package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogElementDescriptorModel {

  private String name;

  /** Behavioral type from descriptor YAML ({@code trigger}, {@code system}, etc.). */
  private String type;

  private Boolean inputEnabled;
  private Boolean outputEnabled;
  private String inputQuantity;
  private Boolean container;
  private Boolean allowedInContainers;
  private Boolean mandatoryInnerElement;
  private List<String> parentRestriction = new ArrayList<>();
  private Map<String, String> allowedChildren = new HashMap<>();
  private CatalogDescriptorPropertiesBlockModel properties;
  private List<CatalogCustomTabModel> customTabs = new ArrayList<>();

  public void setParentRestriction(List<String> parentRestriction) {
    this.parentRestriction = parentRestriction != null ? parentRestriction : new ArrayList<>();
  }

  public void setAllowedChildren(Map<String, String> allowedChildren) {
    this.allowedChildren = allowedChildren != null ? allowedChildren : new HashMap<>();
  }

  public void setCustomTabs(List<CatalogCustomTabModel> customTabs) {
    this.customTabs = customTabs != null ? customTabs : new ArrayList<>();
  }

  public List<CatalogDescriptorPropertyModel> allProperties() {
    return properties != null ? properties.all() : List.of();
  }
}
