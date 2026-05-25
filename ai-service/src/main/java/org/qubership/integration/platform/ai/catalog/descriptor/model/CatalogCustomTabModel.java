package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogCustomTabModel {

  private String name;
  private String uiComponent;
  private CatalogPropertyValidationModel validation;
}
