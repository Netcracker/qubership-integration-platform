package org.qubership.integration.platform.ai.schema;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads raw YAML schema text from the classpath with caching. */
@ApplicationScoped
public class SchemaResourceLoader {

  private final Map<String, String> yamlCache = new ConcurrentHashMap<>();

  public boolean existsElementSchema(String elementType) {
    String path = QipConfModelUris.elementSchemaClasspath(elementType);
    return currentClassLoader().getResource(path) != null;
  }

  public String readElementSchemaYaml(String elementType) {
    return readClasspathYaml(QipConfModelUris.elementSchemaClasspath(elementType));
  }

  /** Reads YAML for a conf-model URI (fragment is ignored for classpath lookup). */
  public String readSchemaYamlByModelUri(String modelUri) {
    String withoutFragment = QipConfModelUris.stripFragment(modelUri);
    String classpath = QipConfModelUris.classpathResourceForModelUri(withoutFragment);
    return readClasspathYaml(classpath);
  }

  public String readClasspathYaml(String classpathLocation) {
    return yamlCache.computeIfAbsent(classpathLocation, this::loadUtf8);
  }

  private String loadUtf8(String classpathLocation) {
    ClassLoader cl = currentClassLoader();
    try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
      if (in == null) {
        throw new SchemaNotFoundException("Schema not found on classpath: " + classpathLocation);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SchemaNotFoundException(
          "Failed to read schema: " + classpathLocation + ": " + e.getMessage());
    }
  }

  private static ClassLoader currentClassLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return cl != null ? cl : SchemaResourceLoader.class.getClassLoader();
  }
}
