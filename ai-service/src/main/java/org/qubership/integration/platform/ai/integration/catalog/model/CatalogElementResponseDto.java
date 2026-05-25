package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Deserialization target for runtime-catalog {@code ElementResponse} (subset used by AI tools). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogElementResponseDto {

  public String id;
  public String name;
  public String description;
  public String type;
  public String parentElementId;
  public String swimlaneId;
  public Map<String, Object> properties;
  public List<CatalogElementResponseDto> children;
  public boolean mandatoryChecksPassed;
}
