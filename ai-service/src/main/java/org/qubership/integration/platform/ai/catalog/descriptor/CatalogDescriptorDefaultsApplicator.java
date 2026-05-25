package org.qubership.integration.platform.ai.catalog.descriptor;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogDescriptorPropertyModel;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;

import java.util.Map;
import java.util.Set;

/**
 * Fills missing {@code properties} entries from catalog descriptor defaults (scalar {@code default}
 * only). Only keys present in {@code schemaAllowedPropertyKeys} are applied so YAML-only fields are
 * not injected before JSON Schema validation.
 */
@ApplicationScoped
public class CatalogDescriptorDefaultsApplicator {

  public void applyDefaults(
      CatalogElementDescriptorModel descriptor,
      Map<String, Object> properties,
      Set<String> schemaAllowedPropertyKeys) {
    if (descriptor == null
        || properties == null
        || schemaAllowedPropertyKeys == null
        || schemaAllowedPropertyKeys.isEmpty()) {
      return;
    }
    for (CatalogDescriptorPropertyModel p : descriptor.allProperties()) {
      if (p.getName() == null || p.getName().isBlank()) {
        continue;
      }
      if (!schemaAllowedPropertyKeys.contains(p.getName())) {
        continue;
      }
      if (p.getDefaultValue() == null) {
        continue;
      }
      if (!properties.containsKey(p.getName()) || properties.get(p.getName()) == null) {
        properties.put(p.getName(), p.getDefaultValue());
      }
    }
  }
}
