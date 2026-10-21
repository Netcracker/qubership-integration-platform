package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Deserialization target for runtime-catalog {@code DependencyResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogDependencyDto {

  public String id;
  public String from;
  public String to;
}
