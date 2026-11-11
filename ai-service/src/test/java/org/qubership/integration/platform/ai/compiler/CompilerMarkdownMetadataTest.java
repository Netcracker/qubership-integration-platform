package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompilerMarkdownMetadataTest {

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  @Test
  void malformedMetadataPreservesFrontmatter() {
    Map<String, String> metadata = CompilerMarkdownMetadata.parse("""
        ---
        generator-id: GEN-01
        ---
        # Generator

        ## Metadata

        ```yaml
        generator-id: [invalid
        ```
        """, yamlMapper);

    assertEquals(Map.of("generator-id", "GEN-01"), metadata);
  }

  @Test
  void scalarValuesAreNormalizedAndNestedValuesAreIgnored() {
    Map<String, String> metadata = CompilerMarkdownMetadata.parse("""
        ---
        Generator ID: GEN-01
        PUBLIC_API: false
        version: 2
        optional: null
        inputs: [one, two]
        nested: {name: ignored}
        ---
        # Generator
        """, yamlMapper);

    assertEquals(Map.of("generator-id", "GEN-01", "public-api", "false",
        "version", "2", "optional", "null"), metadata);
  }
}
