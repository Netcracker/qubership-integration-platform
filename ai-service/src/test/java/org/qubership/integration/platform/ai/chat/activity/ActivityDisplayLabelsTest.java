package org.qubership.integration.platform.ai.chat.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ActivityDisplayLabelsTest {

  @Test
  void knownSkillUsesTable() {
    assertEquals(
        "Parsing requirements", ActivityDisplayLabels.of("skill", "cip-requirement-analyzer"));
  }

  @Test
  void knownToolUsesTable() {
    assertEquals(
        "Searching for a service", ActivityDisplayLabels.of("tool", "searchCatalogSystems"));
  }

  @Test
  void catalogSearchPathUsesHttpTemplate() {
    assertEquals(
        "Searching for a service", ActivityDisplayLabels.of("tool", "POST /v1/systems/search"));
  }

  @Test
  void catalogGetSystemWithUuidUsesHttpTemplate() {
    assertEquals(
        "Loading a service",
        ActivityDisplayLabels.of("tool", "GET /v1/systems/3fa85f64-5717-4562-b3fc-2c963f66afa6"));
  }

  @Test
  void unknownHttpUsesCatalogFallback() {
    assertEquals(
        "Calling the catalog", ActivityDisplayLabels.of("tool", "DELETE /v1/unknown"));
  }

  @Test
  void unknownGeneratorUsesSuffixFallback() {
    assertEquals(
        "Generating widget", ActivityDisplayLabels.of("skill", "cip-widget-generator"));
  }

  @Test
  void cipPrefixedSkillHumanizesWithoutRepeatingPrefix() {
    assertEquals("Running fix planner", ActivityDisplayLabels.of("skill", "cip-fix-planner"));
  }

  @Test
  void unknownToolUsesRunningPlusSplitWords() {
    assertEquals(
        "Running frobnicate payload", ActivityDisplayLabels.of("tool", "frobnicatePayload"));
  }

  @Test
  void pipelineKindIsUnchanged() {
    assertEquals("compile", ActivityDisplayLabels.of("pipeline", "compile"));
  }

  @Test
  void blankAndNullStayAsIs() {
    assertEquals("", ActivityDisplayLabels.of("skill", ""));
    assertNull(ActivityDisplayLabels.of("tool", null));
  }

  @Test
  void longestHttpTemplateWins() {
    assertEquals(
        "Creating the chain", ActivityDisplayLabels.of("tool", "POST /v1/chains"));
    assertEquals(
        "Adding a chain element",
        ActivityDisplayLabels.of("tool", "POST /v1/chains/abc/elements"));
    assertEquals(
        "Moving chain elements",
        ActivityDisplayLabels.of("tool", "POST /v1/chains/abc/elements/transfer"));
  }
}
