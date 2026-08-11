package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequirementBriefTextTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void formatsStructuredBriefFields() {
    RequirementBrief brief =
        new RequirementBrief(
            "Call customer API",
            List.of("packageId: pkg-1", "operationId: getCustomer"),
            List.of("protocol: REST"),
            List.of("API Hub service not resolved"),
            List.of(),
            "Lookup customer by id");

    String formatted = RequirementBriefText.format(brief);

    assertTrue(formatted.contains("Goal: Call customer API"));
    assertTrue(formatted.contains("Summary: Lookup customer by id"));
    assertTrue(formatted.contains("Inputs:"));
    assertTrue(formatted.contains("- packageId: pkg-1"));
    assertTrue(formatted.contains("- operationId: getCustomer"));
    assertTrue(formatted.contains("Constraints:"));
    assertTrue(formatted.contains("- protocol: REST"));
    assertTrue(formatted.contains("Assumptions:"));
    assertTrue(formatted.contains("- API Hub service not resolved"));
  }

  @Test
  void returnsEmptyForNullBrief() {
    assertEquals("", RequirementBriefText.format(null));
  }

  @Test
  void legacyJsonWithoutDataMappingsDecodesToEmptyList() throws Exception {
    String legacyJson =
        """
        {
          "goal": "Call customer API",
          "inputs": ["packageId: pkg-1"],
          "constraints": [],
          "assumptions": [],
          "citations": [],
          "summary": "Lookup customer by id"
        }
        """;

    RequirementBrief brief = objectMapper.readValue(legacyJson, RequirementBrief.class);

    assertTrue(brief.dataMappings().isEmpty());
    assertEquals("Call customer API", brief.goal());
  }
}
