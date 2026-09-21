package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogMcpSystemDto {
  public String id;
  public String name;
  public String identifier;
  public String description;
}
