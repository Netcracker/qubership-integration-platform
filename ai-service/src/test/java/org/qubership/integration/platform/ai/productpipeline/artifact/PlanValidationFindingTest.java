package org.qubership.integration.platform.ai.productpipeline.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlanValidationFindingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void legacyJsonWithoutMappingDetailsRemainsReadable() throws Exception {
    PlanValidationFinding restored =
        MAPPER.readValue(
            """
            {"code":"GAP","message":"missing route","blocker":true}
            """,
            PlanValidationFinding.class);

    assertEquals("GAP", restored.code());
    assertEquals("missing route", restored.message());
    assertTrue(restored.blocker());
    assertFalse(restored.mappingDetails().isPresent());
    assertEquals("", restored.mappingDetails().targetPath());
    assertEquals("", restored.mappingDetails().consumedBriefArtifactId());
    assertEquals("", restored.originRecordId());
    assertEquals("", restored.fieldPointer());
  }

  @Test
  void threeArgumentConstructionKeepsEmptyMappingDetails() {
    PlanValidationFinding finding = new PlanValidationFinding("GAP", "missing route", true);
    assertFalse(finding.mappingDetails().isPresent());
  }

  @Test
  void typedLocationRoundTripsWithoutChangingLegacyWireFields() throws Exception {
    var legacy = new PlanValidationFinding("GAP", "missing route", true);
    var legacyJson = MAPPER.valueToTree(legacy);
    assertFalse(legacyJson.has("originRecordId"));
    assertFalse(legacyJson.has("fieldPointer"));
    var located = new PlanValidationFinding("GAP", "missing route", true, null, "fact-A", "/properties/route");
    assertEquals(located, MAPPER.readValue(MAPPER.writeValueAsString(located), PlanValidationFinding.class));
  }
}
