package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Deserialization target for runtime-catalog {@code ElementDescriptor} (structural subset). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogElementDescriptorDto {

  public String name;
  public boolean container;
  public Map<String, CatalogChildQuantity> allowedChildren;
  public List<String> parentRestriction;
  public boolean ordered;
  public String priorityProperty = "priority";
  public boolean mandatoryInnerElement;
  public boolean deprecated;
  public boolean oldStyleContainer;
  public boolean allowedInContainers = true;
}
