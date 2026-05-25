package org.qubership.integration.platform.ai.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaResourceLoaderTest {

  private final SchemaResourceLoader loader = new SchemaResourceLoader();

  @Test
  void existsElementSchemaServiceCall() {
    assertTrue(loader.existsElementSchema("service-call"));
  }

  @Test
  void readElementSchemaYamlContainsRetryCount() {
    String yaml = loader.readElementSchemaYaml("service-call");
    assertTrue(yaml.contains("retryCount"));
  }

  @Test
  void readSchemaYamlByModelUriOperationFragment() {
    String uri = QipConfModelUris.CONF_MODEL_PREFIX + "element/properties/operation.schema.yaml";
    String yaml = loader.readSchemaYamlByModelUri(uri);
    assertTrue(yaml.contains("integrationOperationProtocolType"));
  }

  @Test
  void missingElementSchemaThrows() {
    assertThrows(
        SchemaNotFoundException.class,
        () -> loader.readElementSchemaYaml("nonexistent-element-type-xyz"));
  }
}
