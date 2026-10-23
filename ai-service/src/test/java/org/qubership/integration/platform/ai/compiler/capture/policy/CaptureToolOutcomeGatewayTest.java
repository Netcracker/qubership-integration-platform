package org.qubership.integration.platform.ai.compiler.capture.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;

class CaptureToolOutcomeGatewayTest {

  private ToolCallFingerprintStore fingerprintStore;
  private CaptureAttemptFeedbackStore feedbackStore;
  private CaptureToolOutcomeGateway gateway;

  @BeforeEach
  void setUp() {
    fingerprintStore = new ToolCallFingerprintStore(new ObjectMapper());
    feedbackStore = new CaptureAttemptFeedbackStore(fingerprintStore);
    gateway = new CaptureToolOutcomeGateway(new CaptureFailurePolicy(), fingerprintStore, feedbackStore);
  }

  @Test
  void parserDetailsNeverRenewAnExistingNativeSoftCredit() {
    for (String code : List.of("INVALID_JSON", "DUPLICATE_JSON_KEY")) {
      String legacy = code + " /: Send one valid JSON argument object. Resubmit the complete captureGraphPatch argument object.";
      String detailed = legacy + ToolCallFingerprints.NATIVE_PARSER_DETAILS
          + "layer=outer; context=/patch/other; line=4; column=98; charOffset=9000; reason=node 'untrusted' property 'text' [x]";
      assertEquals(legacy, ToolCallFingerprints.stripNativeParserDetails(detailed));
      assertEquals(ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", legacy),
          ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", detailed));
      assertEquals(ToolCallFingerprints.failureSignature(legacy), ToolCallFingerprints.failureSignature(detailed));
      assertEquals(ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", legacy),
          ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", " \n" + detailed));
      assertEquals(legacy, gateway.onFailure(CaptureFeedbackChannel.PATCH, code, "owner",
          CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE, "captureGraphPatch", Map.of(), legacy));
      assertThrows(CaptureValidationException.class, () -> gateway.onFailure(CaptureFeedbackChannel.PATCH,
          code, "owner", CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE,
          "captureGraphPatch", Map.of("changed", "arguments"), detailed));
      var refused = feedbackStore.lastPatchFailure(code, "owner").orElseThrow();
      assertEquals(CaptureFailureClass.IDENTICAL_SPAM, refused.failureClass());
      assertFalse(refused.outerAllowed());
      assertEquals(detailed, refused.summary());
      assertEquals(1, fingerprintStore.softCreditsUsed(code));
      for (String suffix : List.of("line=1; charOffset=2; reason=another native cause", " \n node 'other' property 'other'")) {
        assertEquals(ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", legacy),
            ToolCallFingerprints.failureFingerprint("captureGraphPatch", "owner", legacy + ToolCallFingerprints.NATIVE_PARSER_DETAILS + suffix));
      }
    }
    for (String foreign : List.of("INVALID_VALUE /: business", "user text INVALID_JSON /: body", "INVALID_JSON /other: body")) {
      String text = foreign + ToolCallFingerprints.NATIVE_PARSER_DETAILS + "keep this data";
      assertEquals(text, ToolCallFingerprints.stripNativeParserDetails(text));
    }
    assertEquals("INVALID_JSON /: no suffix", ToolCallFingerprints.stripNativeParserDetails("INVALID_JSON /: no suffix"));
  }

  @Test
  void nativeUnknownKeysReceiveCreditPerNodeAndField() {
    String first = "node 'script-a' (script) has unknown property key 'incorrectA'";
    String second = "node 'script-b' (script) has unknown property key 'incorrectB'";
    for (String message : List.of(first, second)) {
      assertEquals(message, gateway.onFailure(CaptureFeedbackChannel.PATCH, "native-fields", "script",
          CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE, "captureGraphPatch",
          Map.of("explanation", message), message));
    }
    assertThrows(CaptureValidationException.class, () -> gateway.onFailure(CaptureFeedbackChannel.PATCH,
        "native-fields", "script", CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE,
        "captureGraphPatch", Map.of("explanation", "Changed payload wording"),
        "  NODE 'script-a' (script)  has unknown property key 'incorrectA'  "));
    for (String node : List.of("script a", "script  a", "script; a")) {
      String message = "node '" + node + "' (script) has unknown property key 'incorrectA'";
      assertEquals(message, gateway.onFailure(CaptureFeedbackChannel.PATCH, "native-identities", "script",
          CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE, "captureGraphPatch", Map.of(), message));
    }
    String combined = "Patch produced invalid graph: " + first + "; " + second;
    assertEquals(combined, gateway.onFailure(CaptureFeedbackChannel.PATCH, "native-order", "script",
        CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE, "captureGraphPatch", Map.of(), combined));
    assertThrows(CaptureValidationException.class, () -> gateway.onFailure(CaptureFeedbackChannel.PATCH,
        "native-order", "script", CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE,
        "captureGraphPatch", Map.of("order", "reversed"), "Patch produced invalid graph: " + second + "; " + first));
    assertFalse(ToolCallFingerprints.failureFingerprint("captureGraphPatch", "script",
        "node '00000000-0000-0000-0000-000000000001' (script) has unknown property key 'x'")
        .equals(ToolCallFingerprints.failureFingerprint("captureGraphPatch", "script",
            "node '00000000-0000-0000-0000-000000000002' (script) has unknown property key 'x'")));
  }

  @Test
  void onFailurePersistsFieldHintsOnPlanChannel() {
    List<CaptureFieldHint> hints =
        List.of(new CaptureFieldHint("patternId", "elementSkeleton.selectedPatternId", "GP-01"));

    gateway.onFailure(
        CaptureFeedbackChannel.PLAN,
        "conv-1",
        null,
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "captureSelectedPattern",
        Map.of("patternId", ""),
        "patternId is required",
        hints);

    CaptureAttemptFeedback feedback = feedbackStore.lastPlanFailure("conv-1").orElseThrow();
    assertEquals(hints, feedback.fieldHints());
  }

  @Test
  void softThenCveOnSameFingerprint() {
    Object args = Map.of("scripts", java.util.List.of());

    String soft =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            args,
            "scripts are required");

    assertEquals("scripts are required", soft);
    CaptureAttemptFeedback first =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.CORRECTABLE, first.failureClass());
    assertTrue(first.outerAllowed());

    CaptureValidationException cve =
        assertThrows(
            CaptureValidationException.class,
            () ->
                gateway.onFailure(
                    CaptureFeedbackChannel.PATCH,
                    "conv-1",
                    "cap-1",
                    CaptureFailureKind.VALIDATION,
                    CaptureFailureClass.CORRECTABLE,
                    "repairScriptBodies",
                    args,
                    "scripts are required"));

    assertTrue(cve.getMessage().contains("Repeated capture validation failure"));
    CaptureAttemptFeedback second =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM, second.failureClass());
    assertFalse(second.outerAllowed());
  }

  @Test
  void rationaleOnlyChangeDoesNotGrantSecondSoft() {
    Object firstArgs = Map.of("scripts", java.util.List.of(), "rationale", "a");
    Object secondArgs = Map.of("scripts", java.util.List.of(), "rationale", "b");

    gateway.onFailure(
        CaptureFeedbackChannel.PATCH,
        "conv-1",
        "cap-1",
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "repairScriptBodies",
        firstArgs,
        "empty");

    assertThrows(
        CaptureValidationException.class,
        () ->
            gateway.onFailure(
                CaptureFeedbackChannel.PATCH,
                "conv-1",
                "cap-1",
                CaptureFailureKind.VALIDATION,
                CaptureFailureClass.CORRECTABLE,
                "repairScriptBodies",
                secondArgs,
                "empty"));
  }

  @Test
  void permanentNeverSoft() {
    CaptureValidationException cve =
        assertThrows(
            CaptureValidationException.class,
            () ->
                gateway.onFailure(
                    CaptureFeedbackChannel.PATCH,
                    "conv-1",
                    "cap-1",
                    CaptureFailureKind.VALIDATION,
                    CaptureFailureClass.PERMANENT,
                    "captureGraphPatch",
                    Map.of("propertyPatches", java.util.List.of()),
                    "script ownership"));

    assertEquals("script ownership", cve.getMessage());
    CaptureAttemptFeedback feedback =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.PERMANENT, feedback.failureClass());
    assertEquals(false, feedback.outerAllowed());
    assertEquals(0, fingerprintStore.softCreditsUsed("conv-1"));
  }

  @Test
  void aReworkedPayloadEarningTheSameRejectionDoesNotBuyASecondSoftCredit() {
    String soft =
        gateway.onFailure(
            CaptureFeedbackChannel.PLAN,
            "conv-1",
            null,
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "captureChainStructure",
            Map.of("graph", Map.of("nodes", java.util.List.of("a", "b", "c"))),
            "structure capture removed existing node 'cc0b0f34'");

    assertEquals("structure capture removed existing node 'cc0b0f34'", soft);

    CaptureValidationException cve =
        assertThrows(
            CaptureValidationException.class,
            () ->
                gateway.onFailure(
                    CaptureFeedbackChannel.PLAN,
                    "conv-1",
                    null,
                    CaptureFailureKind.VALIDATION,
                    CaptureFailureClass.CORRECTABLE,
                    "captureChainStructure",
                    Map.of("graph", Map.of("nodes", java.util.List.of("a", "b", "c", "d", "e"))),
                    "structure capture removed existing node 'b978e8ff'"));

    assertTrue(cve.getMessage().contains("Repeated capture validation failure"));
    assertEquals(
        CaptureFailureClass.IDENTICAL_SPAM,
        feedbackStore.lastPlanFailure("conv-1").orElseThrow().failureClass());
  }

  @Test
  void acceptedAndDuplicateStillCve() {
    assertThrows(
        CaptureValidationException.class, () -> gateway.onTerminalAccept("captured ok"));
    assertThrows(
        CaptureValidationException.class, () -> gateway.onTerminalDuplicate("already captured"));
  }

  @Test
  void multiFingerprintSoftCreditsAreIndependent() {
    String soft1 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "1"))),
            "fail-1");
    String soft2 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "2"))),
            "fail-2");
    String soft3 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "3"))),
            "fail-3");

    assertEquals("fail-1", soft1);
    assertEquals("fail-2", soft2);
    assertEquals("fail-3", soft3);
    assertEquals(3, fingerprintStore.softCreditsUsed("conv-1"));
    // Bound: sequential=3 cuts further softs at the agent layer; policy itself allows distinct fps.
  }

  @Test
  void requirementIssueWordingDoesNotResetItsBudget() {
    String path = "/draft/facts/http-config/elementIntent/requestedProperties/method";
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(
        new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY", path, "Use a supported key."))));
    assertTrue(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(
        new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY", path, "Correct this property."))));
  }

  @Test
  void structuredRequirementIssuesPreserveDistinctFactIds() {
    var first = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY",
        "/draft/facts/fact-a/elementIntent/requestedProperties/method");
    var second = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY",
        "/draft/facts/fact-A/elementIntent/requestedProperties/method");
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(first)));
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(second)));
    assertTrue(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(second)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"456", "draft", "flow", "changes"})
  void numericAndWrapperNamedPropertyKeysKeepSeparateCredits(String propertyKey) {
    String prefix = "/draft/facts/factA/elementIntent/requestedProperties/";
    var first = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY", prefix + "123", "", "factA");
    var second = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY", prefix + propertyKey, "", "factA");
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(first)));
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(second)));
    assertTrue(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(second)));
  }

  @Test
  void propertySpecificRequirementIssuesKeepIndependentCredits() {
    var method = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY",
        "/draft/facts/http-config/elementIntent/requestedProperties/method");
    var route = new CaptureToolOutcomeGateway.TypedIssue("INVALID_REQUESTED_PROPERTY",
        "/draft/facts/http-config/elementIntent/requestedProperties/route");
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(method)));
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(route)));
    assertTrue(gateway.repeatedRequirementIssue("conv-1", "scope-1", List.of(route)));
    assertEquals(2, fingerprintStore.softCreditsUsed("conv-1"));
  }

  @Test
  void requirementIssuesKeepTheirCreditAcrossEditsAndArrayPositions() {
    List<CaptureToolOutcomeGateway.TypedIssue> initial = List.of(
        new CaptureToolOutcomeGateway.TypedIssue(
            "UNKNOWN_INTERACTION_REFERENCE", "/draft/facts/0/interactionIds/0"),
        new CaptureToolOutcomeGateway.TypedIssue(
            "INVALID_VALUE", "/draft/capabilities/1/path"));
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-1", initial));

    List<CaptureToolOutcomeGateway.TypedIssue> remaining = List.of(
        new CaptureToolOutcomeGateway.TypedIssue(
            "UNKNOWN_INTERACTION_REFERENCE", "/changes/updateFacts/2/interactionIds/1"));
    assertTrue(gateway.repeatedRequirementIssue("conv-1", "scope-1", remaining));
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM,
        feedbackStore.lastRequirementFailure("conv-1").orElseThrow().failureClass());
    assertFalse(gateway.repeatedRequirementIssue("conv-1", "scope-2", remaining));
  }
}
