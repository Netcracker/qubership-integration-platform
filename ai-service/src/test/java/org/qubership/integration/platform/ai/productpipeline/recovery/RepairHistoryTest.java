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
  void compactHistoryRetainsIssueIdentityWithoutDuplicatingCurrentSchemaDetails() {
    var raw = mapper.createObjectNode();
    var feedback = raw.putObject("feedback");
    feedback.put("summary", "One field still needs repair");
    var issue = feedback.putArray("issues").addObject().put("code", "enum")
        .put("entityId", "element").put("path", "/properties/mode");
    issue.putObject("details").put("schema", "full constraint ".repeat(1000));
    var saved = raw.deepCopy();
    var projected = RepairHistory.projectValues(List.of(raw), mapper, 5000);
    var projectedFeedback = projected.path("attempts").get(0).path("feedback");
    assertTrue(projectedFeedback.path("issueDetailsOmitted").asBoolean());
    assertEquals("/properties/mode", projectedFeedback.path("issues").get(0).path("path").asText());
    assertEquals("enum", projectedFeedback.path("issues").get(0).path("code").asText());
    assertFalse(projectedFeedback.path("issues").get(0).has("details"));
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
