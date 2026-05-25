package org.qubership.integration.platform.ai.catalog.descriptor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads embedded runtime-catalog element descriptors from {@code
 * catalog-descriptors/elements/<type>/description.yaml}.
 */
@ApplicationScoped
public class CatalogDescriptorResourceLoader {

  static final String ELEMENTS_PREFIX = "catalog-descriptors/elements/";

  private final ObjectMapper yamlMapper;
  private final ConcurrentHashMap<String, Optional<CatalogElementDescriptorModel>> cache =
      new ConcurrentHashMap<>();

  public CatalogDescriptorResourceLoader() {
    this.yamlMapper =
        new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /**
   * Returns empty when no descriptor resource exists for the element type (backward compatible).
   */
  public Optional<CatalogElementDescriptorModel> load(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return Optional.empty();
    }
    String key = elementType.trim();
    return cache.computeIfAbsent(key, this::loadUncached);
  }

  private Optional<CatalogElementDescriptorModel> loadUncached(String elementType) {
    String dir = ELEMENTS_PREFIX + elementType + "/";
    ClassLoader cl = currentClassLoader();
    InputStream in = openDescriptorStream(cl, dir);
    if (in == null) {
      return Optional.empty();
    }
    try (InputStream stream = in) {
      CatalogElementDescriptorModel model =
          yamlMapper.readValue(stream, CatalogElementDescriptorModel.class);
      return Optional.ofNullable(model);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to parse catalog descriptor YAML for " + elementType, e);
    }
  }

  private static InputStream openDescriptorStream(ClassLoader cl, String dir) {
    InputStream yaml = cl.getResourceAsStream(dir + "description.yaml");
    if (yaml != null) {
      return yaml;
    }
    return cl.getResourceAsStream(dir + "description.yml");
  }

  private static ClassLoader currentClassLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return cl != null ? cl : CatalogDescriptorResourceLoader.class.getClassLoader();
  }
}
