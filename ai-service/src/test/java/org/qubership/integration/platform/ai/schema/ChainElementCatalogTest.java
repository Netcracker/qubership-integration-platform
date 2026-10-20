package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChainElementCatalogTest {

  private final ChainElementCatalog catalog = new ChainElementCatalog(new ObjectMapper());
  private final SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();

  @Test
  void readsCurrentAndDeprecatedTypesFromIndex() {
    assertTrue(catalog.isKnown("loop-2"));
    assertTrue(catalog.isKnown("loop-expression"));
    assertFalse(catalog.isDeprecated("loop-2"));
    assertTrue(catalog.isDeprecated("loop-expression"));
    assertTrue(catalog.deprecatedTypes().contains("loop-expression"));
  }

  @Test
  void indexedTypesResolveToSchemaResources() {
    for (String type : catalog.allTypes()) {
      assertTrue(schemaResourceLoader.existsElementSchema(type), type);
    }
  }
}
