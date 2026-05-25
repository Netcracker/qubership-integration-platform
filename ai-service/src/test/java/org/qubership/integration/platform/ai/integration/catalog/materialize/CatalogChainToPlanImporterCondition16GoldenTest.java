package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden import: catalog elements array → materialized {@link
 * org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan} JSON.
 */
class CatalogChainToPlanImporterCondition16GoldenTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void bareJsonArrayMatchesMaterializedPlanJson() throws Exception {
    String arrayJson = readFixture("condition-test-16-elements-array.json");
    CatalogChainToPlanImporter.ChainPlanImportResult r =
        CatalogChainToPlanImporter.create(objectMapper).importFromUiChainJson(arrayJson);

    assertNull(r.plan().getChain());
    assertJsonEquals(readExpectedPlanTree(), objectMapper.valueToTree(r.plan()));
    assertTrue(r.warnings().stream().anyMatch(w -> w.contains("duplicate element id")));
  }

  @Test
  void wrappedWithChainMetadataPreservesChainSection() throws Exception {
    String arrayJson = readFixture("condition-test-16-elements-array.json");
    ObjectNode wrap = objectMapper.createObjectNode();
    wrap.put("name", "Condition test 16");
    wrap.put("description", "Chain to return greetings based on the current minute");
    wrap.set("elements", objectMapper.readTree(arrayJson));
    String json = objectMapper.writeValueAsString(wrap);

    CatalogChainToPlanImporter.ChainPlanImportResult r =
        CatalogChainToPlanImporter.create(objectMapper).importFromUiChainJson(json);

    JsonNode expected = readExpectedPlanTree().deepCopy();
    ObjectNode chain = objectMapper.createObjectNode();
    chain.put("name", "Condition test 16");
    chain.put("description", "Chain to return greetings based on the current minute");
    ((ObjectNode) expected).set("chain", chain);

    assertJsonEquals(expected, objectMapper.valueToTree(r.plan()));
  }

  private static void assertJsonEquals(JsonNode expected, JsonNode actual) {
    if (!expected.equals(actual)) {
      throw new AssertionError(
          "JSON trees differ.\n--- expected ---\n"
              + expected.toPrettyString()
              + "\n--- actual ---\n"
              + actual.toPrettyString());
    }
  }

  private JsonNode readExpectedPlanTree() throws Exception {
    String text = readFixture("condition-test-16-materialized-plan.json");
    return objectMapper.readTree(text);
  }

  private String readFixture(String name) throws Exception {
    Path path =
        Path.of(Objects.requireNonNull(getClass().getResource("/catalog-import/" + name)).toURI());
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
