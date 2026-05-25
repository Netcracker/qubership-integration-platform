package org.qubership.integration.platform.ai.catalog.descriptor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogDescriptorPropertiesBlockModel {

  private List<CatalogDescriptorPropertyModel> common = new ArrayList<>();
  private List<CatalogDescriptorPropertyModel> advanced = new ArrayList<>();
  private List<CatalogDescriptorPropertyModel> hidden = new ArrayList<>();
  private List<CatalogDescriptorPropertyModel> async = new ArrayList<>();

  public void setCommon(List<CatalogDescriptorPropertyModel> common) {
    this.common = common != null ? common : new ArrayList<>();
  }

  public void setAdvanced(List<CatalogDescriptorPropertyModel> advanced) {
    this.advanced = advanced != null ? advanced : new ArrayList<>();
  }

  public void setHidden(List<CatalogDescriptorPropertyModel> hidden) {
    this.hidden = hidden != null ? hidden : new ArrayList<>();
  }

  public void setAsync(List<CatalogDescriptorPropertyModel> async) {
    this.async = async != null ? async : new ArrayList<>();
  }

  public List<CatalogDescriptorPropertyModel> all() {
    return Stream.of(common.stream(), advanced.stream(), hidden.stream(), async.stream())
        .flatMap(s -> s)
        .toList();
  }
}
