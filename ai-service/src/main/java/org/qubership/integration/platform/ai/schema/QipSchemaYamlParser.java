package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Parses embedded QIP YAML schemas into Jackson {@link JsonNode}. */
@ApplicationScoped
public class QipSchemaYamlParser {

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public JsonNode parseYaml(String yaml) {
    try {
      return yamlMapper.readTree(yaml);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to parse YAML schema", e);
    }
  }
}
