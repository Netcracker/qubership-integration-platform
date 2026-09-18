package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElementPatchValidationMessagesTest {

  private SchemaRefResolver resolver;

  @BeforeEach
  void setUp() {
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    QipSchemaYamlParser qipSchemaYamlParser = new QipSchemaYamlParser();
    resolver = new SchemaRefResolver(schemaResourceLoader, qipSchemaYamlParser);
  }

  @Test
  void missingBranchKeysNamesAddressesForManualRabbitmq() {
    ElementPropertiesSchemaModel model =
        ElementPropertiesSchemaModelBuilder.build("rabbitmq-sender-2", resolver);
    String message =
        ElementPatchValidationMessages.missingBranchKeysMessage(
            model,
            Map.of("connectionSourceType", "manual", "exchange", "ex"));
    assertEquals("missing addresses (connectionSourceType=manual)", message);
    assertFalse(message.contains("rabbitConnection"));
  }

  @Test
  void missingBranchKeysNamesVhostClassifierForMaasRabbitmq() {
    ElementPropertiesSchemaModel model =
        ElementPropertiesSchemaModelBuilder.build("rabbitmq-sender-2", resolver);
    String message =
        ElementPatchValidationMessages.missingBranchKeysMessage(
            model,
            Map.of("connectionSourceType", "maas", "exchange", "ex"));
    assertEquals("missing vhostClassifierName (connectionSourceType=maas)", message);
  }
}
