package org.qubership.integration.platform.ai.productpipeline.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PipelineGatesTest {

  @Test
  void aTaggedPromptNamesItsGateAndReadsBackWithoutTheMarker() {
    String tagged = PipelineGates.tag(PipelineGates.MAPPING_GAP, "Mappings are missing.");

    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(tagged).orElseThrow());
    assertEquals("Mappings are missing.", PipelineGates.strip(tagged));
  }

  @Test
  void taggingTwiceLeavesOneMarker() {
    String once = PipelineGates.tag(PipelineGates.IMPORT_SPECIFICATION, "Import it?");
    String twice = PipelineGates.tag(PipelineGates.IDS_PATH_CHOICE, once);

    assertEquals(once, twice);
    assertEquals(PipelineGates.IMPORT_SPECIFICATION, PipelineGates.gateOf(twice).orElseThrow());
  }

  @Test
  void anUntaggedPromptNamesNoGateAndSurvivesStripping() {
    assertTrue(PipelineGates.gateOf("Which system receives the message?").isEmpty());
    assertEquals(
        "Which system receives the message?",
        PipelineGates.strip("Which system receives the message?"));
  }

  @Test
  void blankAndNullPromptsAreSafe() {
    assertTrue(PipelineGates.gateOf(null).isEmpty());
    assertTrue(PipelineGates.gateOf("  ").isEmpty());
    assertEquals("", PipelineGates.strip(null));
    assertEquals("", PipelineGates.strip("  "));
  }

  /** The marker is machine text; a reader must never see it, whatever the question says. */
  @Test
  void strippingLeavesNoMarkerAnywhereInTheText() {
    String tagged =
        PipelineGates.tag(PipelineGates.IDS_PATH_CHOICE, "Quiere un documento de diseno?");

    assertEquals("Quiere un documento de diseno?", PipelineGates.strip(tagged));
    assertTrue(!PipelineGates.strip(tagged).contains("__GATE:"));
  }
}
