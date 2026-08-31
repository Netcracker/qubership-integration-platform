package org.qubership.integration.platform.ai.productpipeline.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
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
  void retaggingNarrativeWithGateMarkerKeepsTheRequestedGate() {
    String tagged =
        PipelineGates.retag(
            PipelineGates.STAGE_RETRY, "The model wrote __GATE:stage-revise__ in its narrative.");

    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(tagged).orElseThrow());
    assertEquals("The model wrote  in its narrative.", PipelineGates.strip(tagged));
  }

  @Test
  void taggingOwnerChoiceNarrativeKeepsThePipelineCandidates() {
    String tagged =
        PipelineGates.tagOwnerChoice(
            "The model wrote __OWNER_CANDIDATES__compiler.", List.of("planning", "analysis"));

    assertEquals(PipelineGates.OWNER_CHOICE, PipelineGates.gateOf(tagged).orElseThrow());
    assertEquals(List.of("planning", "analysis"), PipelineGates.ownerCandidatesOf(tagged));
  }

  /** A typed follow-up at an internal-failure halt stays on the run, like every other halt. */
  @Test
  void theInternalFailureGateIsARecoverableHaltThatRoundTripsThroughTagging() {
    String tagged =
        PipelineGates.tag(PipelineGates.STAGE_INTERNAL_FAILURE, "A step inside the service broke.");

    assertEquals(PipelineGates.STAGE_INTERNAL_FAILURE, PipelineGates.gateOf(tagged).orElseThrow());
    assertEquals("A step inside the service broke.", PipelineGates.strip(tagged));
  }

  @Test
  void taggingInternalFailureNarrativeKeepsThePipelineCandidates() {
    String tagged =
        PipelineGates.tagInternalFailure(
            "A step inside the service broke.", List.of("analysis", "design"));

    assertEquals(PipelineGates.STAGE_INTERNAL_FAILURE, PipelineGates.gateOf(tagged).orElseThrow());
    assertEquals(List.of("analysis", "design"), PipelineGates.ownerCandidatesOf(tagged));
    assertEquals("A step inside the service broke.", PipelineGates.strip(tagged));
  }

  @Test
  void taggingAGuardNamesItAndStripsItFromTheReader() {
    String tagged =
        PipelineGates.tagGuard(
            PipelineGates.tagEscalated("Recovery is exhausted.", List.of("analysis"), false, ""),
            HaltRecoveryGuard.OWNER_ALREADY_REOPENED.name());

    assertEquals(
        HaltRecoveryGuard.OWNER_ALREADY_REOPENED.name(), PipelineGates.guardOf(tagged).orElseThrow());
    assertEquals("Recovery is exhausted.", PipelineGates.strip(tagged));
    assertFalse(PipelineGates.strip(tagged).contains("__GUARD__"));
  }

  @Test
  void everyHaltGateIsRecoverableAndAQuestionGateIsNot() {
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.STAGE_RETRY));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.STAGE_REVISE));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.STAGE_INTERNAL_FAILURE));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.STAGE_ESCALATED));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.OWNER_CHOICE));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.STAGE_CLARIFICATION));
    assertFalse(PipelineGates.isRecoverableHaltGate(PipelineGates.MAPPING_GAP));
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

  @Test
  void strippingHandlesNoMarkersAndBothMarkerKinds() {
    assertEquals("No markers.", PipelineGates.strip("No markers."));
    assertEquals("One marker.", PipelineGates.strip("__GATE:stage-retry__One marker."));
    assertEquals(
        "Both markers.",
        PipelineGates.strip("__GATE:owner-choice__Both markers.__OWNER_CANDIDATES__planning"));
  }

  @Test
  void escalatedHaltCarriesProducerChoicesAndDropEligibilityWithoutLeakingMarkers() {
    String prompt =
        PipelineGates.tagEscalated(
            "The same validation failure happened twice.",
            List.of("requirement-analysis", "design-planning"),
            true,
            "signature-1");

    assertEquals(PipelineGates.STAGE_ESCALATED, PipelineGates.gateOf(prompt).orElseThrow());
    assertEquals(
        List.of("requirement-analysis", "design-planning"),
        PipelineGates.ownerCandidatesOf(prompt));
    assertTrue(PipelineGates.dropElementAllowed(prompt));
    assertEquals("signature-1", PipelineGates.haltIdentityOf(prompt).orElseThrow());
    assertEquals(
        List.of(
            "requirement-analysis",
            "design-planning",
            PipelineGates.DROP_ELEMENT_ACTION,
            PipelineGates.STOP_WITH_REPORT_ACTION),
        PipelineGates.escalatedActionsOf(prompt));
    assertEquals("The same validation failure happened twice.", PipelineGates.strip(prompt));
  }

  @Test
  void anEmptyInternalFailureCardOffersStopWithReport() {
    String tagged =
        PipelineGates.tagInternalFailure("A step inside the service broke.", List.of());

    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        PipelineGates.internalFailureActionsOf(tagged));
  }

  @Test
  void withStrippedBodyKeepsTheGateAndReplacesTheReaderText() {
    String tagged = PipelineGates.tag(PipelineGates.STAGE_REVISE, "old narrative");
    String rebuilt =
        PipelineGates.withStrippedBody(tagged, "No explanation is available. raw evidence");

    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(rebuilt).orElseThrow());
    assertEquals("No explanation is available. raw evidence", PipelineGates.strip(rebuilt));
  }
}
