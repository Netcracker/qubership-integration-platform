package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments.Issue;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class ProductRequirementBriefToolTest {

  private static final String CONVERSATION = "product-brief-test";

  private CaptureSession session;
  private CaptureAttemptFeedbackStore feedback;
  private ProductRequirementBriefTool tool;
  private AtomicReference<Object> candidate;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    feedback = new CaptureAttemptFeedbackStore();
    tool = new ProductRequirementBriefTool(session, feedback, new ObjectMapper(),
        new E2eRecoveryFaultInjector("", ""));
    candidate = new AtomicReference<>();
  }

  @AfterEach
  void clearBinding() {
    ProductCapabilityCaptureContext.unbind(CONVERSATION);
  }

  @Test
  void pinsApprovedFactsFlowTextAndNegativeConstraints() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);

    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"), List.of(),
            "Return Hello world!", List.of(), List.of()), CONVERSATION);

    assertTrue(result.accepted());
    RequirementBrief brief = (RequirementBrief) candidate.get();
    assertEquals(approved.facts(), brief.facts());
    assertEquals(approved.flow(), brief.flow());
    assertEquals(approved.planningText(), brief.approvedDraftText());
    assertTrue(brief.constraints().contains("No service calls"));
    assertTrue(brief.constraints().contains("No error handling"));
    assertTrue(brief.entryPoints().stream()
        .anyMatch(entry -> "greetings".equals(entry.entryPointId())));
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void rejectedCapturePreservesApprovedDraftAndAllowsCorrection() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);

    ProductRequirementBriefTool.Result rejected = tool.capture(
        new RequirementAnalysisCapture("", List.of(), List.of(), "", List.of(), List.of()),
        CONVERSATION);

    assertFalse(rejected.accepted());
    assertEquals("REPAIR_CAPTURE", rejected.nextAction());
    assertEquals("MISSING_SUMMARY", rejected.issues().getFirst().code());
    assertEquals(approved, ProductCapabilityCaptureContext.binding(CONVERSATION)
        .orElseThrow().approvedDraft());
    assertTrue(session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).isEmpty());

    ProductRequirementBriefTool.Result accepted = tool.capture(
        new RequirementAnalysisCapture("Greetings", List.of(), List.of(), "Hello world!",
            List.of(), List.of()), CONVERSATION);
    assertTrue(accepted.accepted());
    assertTrue(feedback.lastPlanFailure(CONVERSATION).isEmpty());
  }

  @Test
  void rejectsUnknownFieldBeforeBindingWithoutPublishingCandidate() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        RequirementFactFixtures.greetingsApprovedDraft(), candidate::set);

    String result = tool.rejectArguments(CONVERSATION,
        new Issue("UNEXPECTED_FIELD", "/capture/facts", "Remove this unsupported field."));

    assertTrue(result.contains("UNEXPECTED_FIELD"));
    assertTrue(result.contains("REPAIR_CAPTURE"));
    assertTrue(candidate.get() == null);
    assertTrue(feedback.lastPlanFailure(CONVERSATION).isPresent());
  }

  @Test
  void acceptedBriefRejectsSecondCapture() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        RequirementFactFixtures.greetingsApprovedDraft(), candidate::set);
    RequirementAnalysisCapture capture = new RequirementAnalysisCapture(
        "Greetings", List.of(), List.of(), "Hello world!", List.of(), List.of());

    assertTrue(tool.capture(capture, CONVERSATION).accepted());
    ProductRequirementBriefTool.Result duplicate = tool.capture(capture, CONVERSATION);

    assertFalse(duplicate.accepted());
    assertEquals("STOP", duplicate.nextAction());
    assertEquals("DUPLICATE_CAPTURE", duplicate.issues().getFirst().code());
  }

  @Test
  void assignsMappingIdentityAndPortsFromApprovedFlow() {
    RequirementFlow flow = new RequirementFlow(
        List.of(new Interaction("http-in", Direction.INBOUND, "Caller", "POST /tasks", ""),
            new Interaction("http-out", Direction.OUTBOUND, "Tasks", "POST /tasks", "")),
        List.of(new Transition("http-in", "http-out")));
    RequirementDraft approved = new RequirementDraft(true, "Forward a task with a renamed field",
        DraftDecision.READY_FOR_PLAN, List.of(), "brainstorming", "1", null, null, false,
        List.of(RequirementFactFixtures.httpTriggerFact("http-in", "POST", "/tasks"),
            new RequirementFact("http-out", RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY, "http-sender", "Call Tasks", "", "", "",
                "POST", "/tasks")),
        false, null, null, flow, List.of());
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved,
        "draft-artifact-1", candidate::set);

    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Forward task", List.of(), List.of(),
            "Rename name to title", List.of(), List.of(
                new RequirementAnalysisCapture.Mapping("http-in", "http-out",
                    List.of(new RequirementAnalysisCapture.Rule("name", "title", null)), null))),
        CONVERSATION);

    assertTrue(result.accepted(), result.toString());
    RequirementBrief brief = (RequirementBrief) candidate.get();
    assertEquals("draft-artifact-1", brief.approvedDraftReference());
    assertEquals(1, brief.mappingIntents().size());
    assertFalse(brief.mappingIntents().getFirst().mappingIntentId().isBlank());
    assertEquals(MappingPort.OUTPUT, brief.mappingIntents().getFirst().sourcePort());
    assertEquals(MappingPort.REQUEST, brief.mappingIntents().getFirst().targetPort());
  }

  @Test
  void controlledRejectionKeepsDraftAndRepairsOnNextCapture() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    tool = new ProductRequirementBriefTool(session, feedback, new ObjectMapper(),
        new E2eRecoveryFaultInjector("Create chain named", "requirement-analysis=CONTRACT_SHAPE:1"));
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);
    RequirementAnalysisCapture capture = new RequirementAnalysisCapture(
        "Greetings", List.of(), List.of(), "Hello world!", List.of(), List.of());

    ProductRequirementBriefTool.Result first = tool.capture(capture, CONVERSATION);
    assertEquals("INJECTED_BRIEF_REJECTION", first.issues().getFirst().code());
    assertEquals("REPAIR_CAPTURE", first.nextAction());
    assertTrue(candidate.get() == null);
    assertEquals(approved, ProductCapabilityCaptureContext.binding(CONVERSATION)
        .orElseThrow().approvedDraft());

    assertTrue(tool.capture(capture, CONVERSATION).accepted());
    assertTrue(candidate.get() instanceof RequirementBrief);
  }

  @Test
  void rejectsMappingWithoutTargetFieldBeforePublishingBrief() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        RequirementFactFixtures.greetingsApprovedDraft(), candidate::set);

    ProductRequirementBriefTool.Result rejected = tool.capture(
        new RequirementAnalysisCapture("Greetings", List.of(), List.of(), "Hello world!",
            List.of(), List.of(new RequirementAnalysisCapture.Mapping("greetings", "greetings",
                List.of(new RequirementAnalysisCapture.Rule("name", "", null)), null))),
        CONVERSATION);

    assertEquals("MAPPING_TARGET_REQUIRED", rejected.issues().getFirst().code());
    assertEquals("/mappingIntents/0/rules/0/targetPath", rejected.issues().getFirst().path());
    assertTrue(candidate.get() == null);
  }

  @Test
  void missingAnalysisSessionStopsAndRecordsTechnicalFailure() {
    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Greetings", List.of(), List.of(), "Hello world!",
            List.of(), List.of()), CONVERSATION);

    assertEquals("ANALYSIS_SESSION_NOT_FOUND", result.issues().getFirst().code());
    assertEquals("STOP", result.nextAction());
    assertTrue(feedback.lastPlanFailure(CONVERSATION).orElseThrow().summary()
        .contains("No active chat session"));
  }

  @Test
  void rejectsMappingThatChangesAnApprovedSourceField() {
    RequirementDraft approved = approvedWithTypedMapping();
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);

    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Forward task", List.of(), List.of(),
            "Copy title to Subject", List.of(), List.of(
                new RequirementAnalysisCapture.Mapping("http-in", "http-out",
                    List.of(new RequirementAnalysisCapture.Rule("name", "Subject", null)), null))),
        CONVERSATION);

    assertFalse(result.accepted(), "The approved title field must not become name in the brief");
    assertTrue(candidate.get() == null);
  }

  @Test
  void projectsApprovedFieldMappingWhenModelOmitsIt() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        approvedWithTypedMapping(), candidate::set);

    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Forward task", List.of(), List.of(),
            "Copy title to Subject", List.of(), List.of()), CONVERSATION);

    assertTrue(result.accepted(), result.toString());
    RequirementBrief brief = (RequirementBrief) candidate.get();
    assertEquals(1, brief.mappingIntents().size());
    assertEquals("title", brief.mappingIntents().getFirst().rules().getFirst().sourcePath());
    assertEquals("Subject", brief.mappingIntents().getFirst().rules().getFirst().targetPath());
    assertEquals(MappingPort.OUTPUT, brief.mappingIntents().getFirst().sourcePort());
    assertEquals(MappingPort.REQUEST, brief.mappingIntents().getFirst().targetPort());
  }

  private static RequirementDraft approvedWithTypedMapping() {
    RequirementCaptureInput.DraftInput input = new RequirementCaptureInput.DraftInput(
        new RequirementCaptureInput.FlowInput(
            List.of(new RequirementCaptureInput.InteractionInput("http-in", Direction.INBOUND,
                    "Caller", "POST /tasks", "Receive task", null, null),
                new RequirementCaptureInput.InteractionInput("http-out", Direction.OUTBOUND,
                    "Tasks", "POST /tasks", "Send task", null, null)),
            List.of(new RequirementCaptureInput.TransitionInput("http-in", "http-out"))),
        List.of(new RequirementCaptureInput.FactInput("goal", List.of("http-in"),
                RequirementCaptureInput.FactKind.GOAL,
                RequirementCaptureInput.Polarity.POSITIVE, "Forward the task"),
            new RequirementCaptureInput.FactInput("mapping-title-subject",
                List.of("http-in", "http-out"),
                RequirementCaptureInput.FactKind.FIELD_MAPPING,
                RequirementCaptureInput.Polarity.POSITIVE,
                "Copy the incoming title to the downstream Subject field.",
                new RequirementCaptureInput.FieldMappingInput(
                    "http-in", "title", "http-out", "Subject", null))),
        List.of(new RequirementCaptureInput.CapabilityInput("http-in", "http-trigger",
                RequirementCaptureInput.HttpMode.CUSTOM, RequirementCaptureInput.HttpMethod.POST,
                "/tasks", null, null, null),
            new RequirementCaptureInput.CapabilityInput("http-out", "http-sender", null,
                RequirementCaptureInput.HttpMethod.POST, "https://example.com/tasks", null,
                null, null)),
        List.of(), new RequirementCaptureInput.DraftSettings(null, null));
    return RequirementCaptureProjection.toDraft(input, null, List.of(), "1", null);
  }
}
