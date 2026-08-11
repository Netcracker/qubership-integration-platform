package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schema-derived element type facts from {@code qip-schemas/element-index.json}. */
@ApplicationScoped
public class ChainElementCatalog {

  private static final String INDEX_RESOURCE = "qip-schemas/element-index.json";

  private final Map<String, ElementEntry> elementsByType;

  @Inject
  public ChainElementCatalog(ObjectMapper objectMapper) {
    this(loadIndex(objectMapper));
  }

  ChainElementCatalog(ElementIndex index) {
    Map<String, ElementEntry> byType = new LinkedHashMap<>();
    if (index != null && index.elements() != null) {
      for (ElementEntry entry : index.elements()) {
        if (entry.type() != null && !entry.type().isBlank()) {
          byType.put(entry.type().trim(), entry);
        }
      }
    }
    this.elementsByType = Map.copyOf(byType);
  }

  public boolean isKnown(String type) {
    return type != null && elementsByType.containsKey(type.trim());
  }

  public boolean isDeprecated(String type) {
    if (type == null) {
      return false;
    }
    ElementEntry entry = elementsByType.get(type.trim());
    return entry != null && entry.deprecated();
  }

  public Set<String> allTypes() {
    return elementsByType.keySet();
  }

  public Set<String> deprecatedTypes() {
    return elementsByType.entrySet().stream()
        .filter(entry -> entry.getValue().deprecated())
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static ElementIndex loadIndex(ObjectMapper objectMapper) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = ChainElementCatalog.class.getClassLoader();
    }
    try (InputStream in = cl.getResourceAsStream(INDEX_RESOURCE)) {
      if (in == null) {
        throw new SchemaNotFoundException("Schema element index not found: " + INDEX_RESOURCE);
      }
      return objectMapper.readValue(in, ElementIndex.class);
    } catch (IOException e) {
      throw new SchemaNotFoundException("Failed to read schema element index: " + e.getMessage());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ElementIndex(List<ElementEntry> elements) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ElementEntry(String type, String title, boolean deprecated) {}
}
