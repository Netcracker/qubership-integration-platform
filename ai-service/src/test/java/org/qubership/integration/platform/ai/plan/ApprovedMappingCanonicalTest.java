package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class ApprovedMappingCanonicalTest {
  private static final String CHAT = "canonical-approved-mapping";
  private final CaptureAttemptFeedbackStore feedback = new CaptureAttemptFeedbackStore();
  private final ProductRequirementBriefTool tool = new ProductRequirementBriefTool(
      new CaptureSession(), feedback, new ObjectMapper(), new E2eRecoveryFaultInjector("", ""));
  private final AtomicReference<Object> candidate = new AtomicReference<>();

  @AfterEach
  void clear() {
    ProductCapabilityCaptureContext.unbind(CHAT);
  }

  @ParameterizedTest
  @CsvSource({"Receipt,$.Receipt", "$.Receipt,Receipt", "Receipt,Receipt", "$.Receipt,$.Receipt"})
  void acceptsOnlyTheTargetRepresentationDifference(String approvedTarget, String proposedTarget) {
    var approved = draft("$.parcel", approvedTarget, null);
    ProductCapabilityCaptureContext.bindAnalysis("run", CHAT, approved, candidate::set);
    var result = tool.capture(capture("ingress", "delivery", "$.parcel", proposedTarget, null), CHAT);
    assertTrue(result.accepted(), result.toString());
    assertEquals(RequirementBriefProjector.authoredMappings(approved).getFirst().rules(),
        ((RequirementBrief) candidate.get()).mappingIntents().getFirst().rules());
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "target", "owner", "constant", "behavior"})
  void rejectsEveryNonrepresentationalChange(String change) {
    var approved = draft("$.parcel", "Receipt", null);
    ProductCapabilityCaptureContext.bindAnalysis("run", CHAT, approved, candidate::set);
    var result = tool.capture(capture("ingress", change.equals("owner") ? "ingress" : "delivery",
        change.equals("source") ? "$.differentParcel" : "$.parcel",
        change.equals("target") ? "$.DifferentReceipt" : "$.Receipt",
        change.equals("constant") ? "Constant changed" : change.equals("behavior") ? "Use another value" : null), CHAT);
    assertFalse(result.accepted(), result.toString());
    assertTrue(result.message().startsWith("Field mapping differs from the approved requirement:"));
    assertEquals(null, candidate.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"$.Receipt", "Receipt"})
  void currentResponseCopyDoesNotReplaceRetainedSourceBehavior(String target) {
    ProductCapabilityCaptureContext.bindAnalysis("run", CHAT,
        draft(null, "Receipt", "Read the retained original parcel from ingress"), candidate::set);
    var result = tool.capture(capture("ingress", "delivery", "$.parcel", target, null), CHAT);
    assertFalse(result.accepted(), result.toString());
    assertEquals(null, candidate.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameDefectCannotGainAnotherCreditFromItsRuleDiagnostics(boolean exactProposal) {
    ProductCapabilityCaptureContext.bindAnalysis("run", CHAT,
        draft("$.parcel", "Receipt", null), candidate::set);
    var first = capture("ingress", "delivery", "$.wrongParcel", "$.Receipt", null);
    assertFalse(tool.capture(first, CHAT).accepted());
    String originalSummary = feedback.lastPlanFailure(CHAT).orElseThrow().summary();
    var next = exactProposal ? first
        : capture("ingress", "delivery", "$.anotherWrongParcel", "$.Receipt", null);
    assertThrows(CaptureValidationException.class, () -> tool.capture(next, CHAT));
    var terminal = feedback.lastPlanFailure(CHAT).orElseThrow();
    assertEquals(originalSummary, terminal.summary());
    assertEquals("IDENTICAL_SPAM", terminal.failureClass().name());
    assertFalse(terminal.outerAllowed());
    assertEquals(exactProposal, terminal.noProgress());
    assertEquals(null, candidate.get());
  }

  static RequirementAnalysisCapture capture(String source, String target, String sourcePath,
      String targetPath, String expression) {
    return new RequirementAnalysisCapture("Deliver parcels", List.of("POST /parcels"), List.of(),
        "Deliver the approved parcel receipt", List.of(), List.of(
            new RequirementAnalysisCapture.Mapping(source, target, List.of(
                new RequirementAnalysisCapture.Rule(sourcePath, targetPath, expression)), null)));
  }

  static RequirementDraft draft(String sourcePath, String targetPath, String expression) {
    var input = new RequirementCaptureInput.DraftInput(new RequirementCaptureInput.FlowInput(
        List.of(new RequirementCaptureInput.InteractionInput("ingress", Direction.INBOUND,
                "Caller", "POST /parcels", "Receive parcels", null, null),
            new RequirementCaptureInput.InteractionInput("delivery", Direction.OUTBOUND,
                "Delivery", "POST /receipts", "Deliver receipts", null, null)),
        List.of(new RequirementCaptureInput.TransitionInput("ingress", "delivery"))),
        List.of(new RequirementCaptureInput.FactInput("parcel-goal", List.of("ingress"),
                RequirementCaptureInput.FactKind.GOAL, RequirementCaptureInput.Polarity.POSITIVE,
                "Deliver parcels"),
            new RequirementCaptureInput.FactInput("parcel-receipt", List.of("ingress", "delivery"),
                RequirementCaptureInput.FactKind.FIELD_MAPPING, RequirementCaptureInput.Polarity.POSITIVE,
                "Preserve the approved parcel receipt", new RequirementCaptureInput.FieldMappingInput(
                    "ingress", sourcePath, "delivery", targetPath, expression))),
        List.of(new RequirementCaptureInput.CapabilityInput("ingress", "http-trigger",
                RequirementCaptureInput.HttpMode.CUSTOM, RequirementCaptureInput.HttpMethod.POST,
                "/parcels", null, null, null),
            new RequirementCaptureInput.CapabilityInput("delivery", "http-sender", null,
                RequirementCaptureInput.HttpMethod.POST, "https://example.com/receipts", null, null, null)),
        List.of(), new RequirementCaptureInput.DraftSettings(false, null));
    return RequirementCaptureProjection.toDraft(input, null, List.of(), "1", null);
  }
}
