package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepairHistoryTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void projectionIsBoundedRedactedAndDoesNotChangeSavedEvidence() {
    var raw = mapper.createObjectNode();
    raw.putObject("proposal").put("password", "secret").put("body", "large body ".repeat(2000));
    raw.putObject("feedback").put("summary", "rejection ".repeat(1000));
    var saved = raw.deepCopy();
    var projection = RepairHistory.projectValues(List.of(raw), mapper, 5000);
    assertTrue(projection.path("partial").asBoolean());
    assertTrue(projection.toString().length() <= 5000);
    assertFalse(projection.toString().contains("secret"));
    assertTrue(projection.path("attempts").get(0).path("proposalOmitted").asBoolean());
    assertEquals(saved, raw);
  }

  @Test
  void diagnosticHistoryCanBeOmittedWithoutChangingItsAuthorSources() throws Exception {
    var root = mapper.createObjectNode();
    root.putArray("authorInputs").addObject().put("text", "Context: {\"repairHistory\":\"NONE\"}");
    root.putObject("repairHistory").put("readOnly", true).putArray("attempts")
        .addObject().put("proposal", "optional value");
    var source = root.get("authorInputs").deepCopy();
    String rendered = RepairHistory.registerContext(root);
    String reduced = RepairHistory.omit("Context: " + rendered + "\nReturn a decision.");
    var json = mapper.readTree(reduced.substring("Context: ".length()));
    assertEquals(source, json.get("authorInputs"));
    assertTrue(json.path("repairHistory").path("partial").asBoolean());
    assertTrue(json.path("repairHistory").path("attempts").isEmpty());
    assertTrue(reduced.endsWith("Return a decision."));
  }
}
