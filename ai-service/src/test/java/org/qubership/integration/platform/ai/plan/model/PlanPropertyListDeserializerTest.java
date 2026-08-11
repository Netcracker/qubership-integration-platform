package org.qubership.integration.platform.ai.plan.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanPropertyListDeserializerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void coercesRawScriptStringArrayToScriptProperty() throws Exception {
    String json =
        """
        {
          "nodeId": "script",
          "type": "script",
          "label": "Return Hello World",
          "parentNodeId": null,
          "order": null,
          "properties": ["def x = 'Hello world!'\\nreturn x"]
        }
        """;

    ChainPlanNode node = objectMapper.readValue(json, ChainPlanNode.class);

    assertNotNull(node.properties());
    assertEquals(1, node.properties().size());
    assertEquals("script", node.properties().get(0).key());
    assertEquals("def x = 'Hello world!'\nreturn x", node.properties().get(0).value());
  }

  @Test
  void keepsStructuredKeyValueProperties() throws Exception {
    String json =
        """
        {
          "nodeId": "http-trigger",
          "type": "http-trigger",
          "label": "Receive Greetings Request",
          "properties": [
            {"key": "contextPath", "value": "/greetings"},
            {"key": "httpMethodRestrict", "value": "GET"},
            {"key": "externalRoute", "value": "false"}
          ]
        }
        """;

    ChainPlanNode node = objectMapper.readValue(json, ChainPlanNode.class);

    assertEquals(3, node.properties().size());
    assertEquals("contextPath", node.properties().get(0).key());
    assertEquals("/greetings", node.properties().get(0).value());
  }

  @Test
  void coercesBareScriptStringPropertiesField() throws Exception {
    String json =
        """
        {
          "nodeId": "script",
          "type": "script",
          "label": "Return Hello World",
          "properties": "return 'ok';"
        }
        """;

    ChainPlanNode node = objectMapper.readValue(json, ChainPlanNode.class);

    assertEquals(List.of(new PlanProperty("script", "return 'ok';")), node.properties());
  }
}
