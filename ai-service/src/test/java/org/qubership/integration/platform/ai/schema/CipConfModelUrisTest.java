package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CipConfModelUrisTest {

  @Test
  void resolvesCurrentElementAndSharedPropertySchemas() {
    assertEquals(
        "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/element/service-call",
        CipConfModelUris.elementModelUri("service-call"));
    assertEquals(
        "qip-schemas/element/service-call.schema.yaml",
        CipConfModelUris.classpathResourceForModelUri(CipConfModelUris.elementModelUri("service-call")));
    assertEquals(
        "qip-schemas/common-properties/element.schema.yaml",
        CipConfModelUris.classpathResourceForModelUri(
            CipConfModelUris.CONF_MODEL_PREFIX + "common-properties/element.schema.yaml"));
  }

  @Test
  void preservesLegacySchemaUris() {
    assertEquals(
        "qip-schemas/element/service-call.schema.yaml",
        CipConfModelUris.classpathResourceForModelUri(
            "http://qubership.org/schemas/product/qip/element/service-call.schema.yaml"));
  }

  @Test
  void rejectsUnsupportedSchemaUris() {
    assertThrows(
        SchemaNotFoundException.class,
        () -> CipConfModelUris.classpathResourceForModelUri("https://example.com/element/service-call"));
  }
}
