package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @ValueSource(strings = {"Subject", "$.Subject"})
  void qualifiedCopyNormalizesItsTargetBeforeApproval(String target) throws Exception {
    var brief = captureTypedBrief("$.title", typedSourceSchema(false),
        new RequirementCaptureInput.DraftSettings(null, null), target);
    var rule = brief.mappingIntents().getFirst().rules().getFirst();
    assertEquals("$.Subject", rule.targetPath());
    assertEquals(new MappingValue.Copy(new MappingSource.Message("http-in", MappingPort.OUTPUT, "$.title")), rule.value());
    assertEquals("ERROR", rule.missingSourcePolicy());
    assertEquals(java.util.Set.of("source", "target"), rule.schemaPins().keySet());
  }

  @Test
  void typedCapturePinsOriginsAndPolicyInTheStoredBrief() throws Exception {
    var brief = captureTypedBrief("$.title", typedSourceSchema(false));
    var blobs = new InMemoryArtifactBlobStore();
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var artifacts = new CompilationArtifacts(
        blobs, mapper, java.time.Clock.systemUTC());
    var revision = artifacts.append(new CompilationArtifacts.AppendCommand(
        CONVERSATION, CompilationArtifacts.Kind.REQUIREMENT_BRIEF,
        "1", "requirement-analysis", "1", brief, List.of(), null));
    var reader = new CompilationArtifacts(
        blobs, mapper, java.time.Clock.systemUTC());
    var restored = reader.payload(reader.get(CONVERSATION, revision.reference()).orElseThrow(), RequirementBrief.class);
    var rule = restored.mappingIntents().getFirst().rules().getFirst();
    assertEquals(new MappingValue.Copy(
        new MappingSource.Message(
            "http-in", MappingPort.OUTPUT, "$.title")), rule.value());
    assertEquals("ERROR", rule.missingSourcePolicy());
    assertEquals(java.util.Map.of("source", "ac551e2eb8fd112dbcb1a219cbed57416ec4dcb6cba18cbe5260e3577fb54fe9",
        "target", "3565b4f04b97097afcde659760002894f32d1b00116b40b17672f49d887a6591"), rule.schemaPins());
    assertTrue(restored.approvedDraftText().contains("missing=ERROR"), restored.approvedDraftText());
    assertTrue(restored.approvedDraftText().contains("http-in/OUTPUT:$.title"), restored.approvedDraftText());
  }

  @Test
  void changedPinnedSchemaNeedsNewApprovalAndStalesTheDependentPlan() throws Exception {
    var first = captureTypedBrief("$.title", typedSourceSchema(false));
    var changed = captureTypedBrief("$.title", typedSourceSchema(true));
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var artifacts = new CompilationArtifacts(
        new InMemoryArtifactBlobStore(), mapper, java.time.Clock.systemUTC());
    var original = artifacts.append(new CompilationArtifacts.AppendCommand(
        CONVERSATION, CompilationArtifacts.Kind.REQUIREMENT_BRIEF,
        "1", "requirement-analysis", "1", first, List.of(), null));
    artifacts.recordDecision(new CompilationArtifacts.DecisionCommand(
        CONVERSATION, original.reference(), CompilationArtifacts.Decision.APPROVED,
        "author", "Confirmed the pinned copy"));
    var plan = artifacts.append(new CompilationArtifacts.AppendCommand(
        CONVERSATION, CompilationArtifacts.Kind.IMPLEMENTATION_PLAN,
        "1", "design-planner", "1", java.util.Map.of("copy", "approved-copy"), List.of(original.reference()), null));
    var replacement = artifacts.append(new CompilationArtifacts.AppendCommand(
        CONVERSATION, CompilationArtifacts.Kind.REQUIREMENT_BRIEF,
        "1", "requirement-analysis", "1", changed, List.of(), original.artifactId()));
    org.junit.jupiter.api.Assertions.assertNotEquals(original.contentHash(), replacement.contentHash());
    assertTrue(artifacts.isApproved(CONVERSATION, original.reference()), original.toString());
    assertFalse(artifacts.isApproved(CONVERSATION, replacement.reference()), replacement.toString());
    assertEquals(List.of(plan.reference()), artifacts.changeImpact(CONVERSATION, replacement.reference())
        .staleDescendants().stream().map(CompilationArtifacts.Revision::reference).toList());
    assertEquals(original.contentHash(), artifacts.get(CONVERSATION, original.reference()).orElseThrow().contentHash());
  }

  @Test
  void changingTypedOriginChangesTheBriefIdentity() throws Exception {
    var first = captureTypedBrief("$.title", typedSourceSchema(false));
    var changed = captureTypedBrief("$.alternateTitle", typedSourceSchema(false));
    org.junit.jupiter.api.Assertions.assertNotEquals(
        CaptureProposal.digest(first),
        CaptureProposal.digest(changed));
    assertEquals("$.alternateTitle", changed.mappingIntents().getFirst().rules().getFirst().sourcePath());
  }

  @Test
  void typedCaptureWithoutSchemaValidationCannotPublishABrief() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approvedWithExplicitOrigin("$.title"), candidate::set);
    var result = tool.capture(new RequirementAnalysisCapture("Forward task", List.of("Task input"), List.of(),
        "Copy the original title", List.of(), List.of()), CONVERSATION);
    assertEquals("MAPPING_SCHEMA_REQUIRED", result.issues().getFirst().code());
    assertFalse(result.accepted(), result.toString());
    assertEquals(null, candidate.get());
    assertEquals(java.util.Optional.empty(), session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class));
  }

  private RequirementBrief captureTypedBrief(String path, String sourceSchema) throws Exception {
    return captureTypedBrief(path, sourceSchema, new RequirementCaptureInput.DraftSettings(null, null));
  }

  private RequirementBrief captureTypedBrief(String path, String sourceSchema,
      RequirementCaptureInput.DraftSettings settings) throws Exception {
    return captureTypedBrief(path, sourceSchema, settings, "$.Subject");
  }

  private RequirementBrief captureTypedBrief(String path, String sourceSchema,
      RequirementCaptureInput.DraftSettings settings, String targetPath) throws Exception {
    var mapper = new ObjectMapper();
    var schemaMaps = java.util.Map.of(
        "op-http-in", new OperationSchemaMaps("op-http-in",
            java.util.Map.of("application/json", mapper.readTree(sourceSchema)), java.util.Map.of()),
        "op-http-out", new OperationSchemaMaps("op-http-out",
            java.util.Map.of("application/json", mapper.readTree("""
                {"type":"object","properties":{"Subject":{"type":"string"}}}
                """)), java.util.Map.of()));
    var localSession = new CaptureSession();
    var localFeedback = new CaptureAttemptFeedbackStore();
    var gateway = new CaptureToolOutcomeGateway(
        new ToolCallFingerprintStore(mapper), localFeedback);
    var pinnedTool = new ProductRequirementBriefTool(localSession, localFeedback, mapper,
        new E2eRecoveryFaultInjector("", ""), gateway, null,
        new BriefCopyMappingValidator(new ReadOnlyCopySchemas(schemaMaps), mapper));
    candidate.set(null);
    ProductCapabilityCaptureContext.unbind(CONVERSATION);
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approvedWithExplicitOrigin(path, settings, targetPath), candidate::set);
    var result = pinnedTool.capture(new RequirementAnalysisCapture("Forward task", List.of("Task input"), List.of(),
        "Copy the original title", List.of(), List.of()), CONVERSATION);
    assertTrue(result.accepted(), result.toString());
    var captured = localSession.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).orElseThrow();
    assertEquals(candidate.get(), captured);
    return captured;
  }

  private static String typedSourceSchema(boolean constrained) {
    return constrained
        ? "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"maxLength\":50},\"alternateTitle\":{\"type\":\"string\"}}}"
        : "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"alternateTitle\":{\"type\":\"string\"}}}";
  }

  private static RequirementDraft approvedWithExplicitOrigin(String sourcePath) {
    return approvedWithExplicitOrigin(sourcePath, new RequirementCaptureInput.DraftSettings(null, null));
  }

  private static RequirementDraft approvedWithExplicitOrigin(String sourcePath,
      RequirementCaptureInput.DraftSettings settings) {
    return approvedWithExplicitOrigin(sourcePath, settings, "$.Subject");
  }

  private static RequirementDraft approvedWithExplicitOrigin(String sourcePath,
      RequirementCaptureInput.DraftSettings settings, String targetPath) {
    var base = approvedWithTypedMapping(settings).authoredDraft();
    var facts = base.facts().stream().map(fact -> fact.fieldMapping() == null ? fact
        : new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(),
            fact.text(), new RequirementCaptureInput.FieldMappingInput("http-in", null, "http-out", targetPath, null,
                new MappingValue.Copy(
                    new MappingSource.Message(
                        "http-in", MappingPort.OUTPUT, sourcePath))))).toList();
    var flow = new RequirementCaptureInput.FlowInput(List.of(
        new RequirementCaptureInput.InteractionInput("http-in", Direction.INBOUND, "OM", "onTaskStart", null, null, null),
        new RequirementCaptureInput.InteractionInput("http-out", Direction.OUTBOUND, "Tasks", "POST /tasks", null, null, null)),
        base.flow().transitions());
    var input = new RequirementCaptureInput.DraftInput(flow, facts,
        List.of(new RequirementCaptureInput.CapabilityInput("http-in", "async-api-trigger",
            null, null, null, null, null, null)), List.of(), base.settings());
    var bindings = List.of(copyBinding("http-in"), copyBinding("http-out"));
    return RequirementCaptureProjection.toDraft(RequirementCaptureProjection.withCopyPolicies(input), null,
        bindings, "1", "source-hash", java.util.Map.of());
  }

  private static CatalogBindingHint copyBinding(String id) {
    return new CatalogBindingHint("3",
        id, id, "http-in".equals(id) ? "onTaskStart" : "POST /tasks", "system", "group", "spec", "op-" + id,
        "http-in".equals(id) ? "kafka" : "HTTP", "http-in".equals(id) ? "publish" : "POST", "/tasks", "1",
        java.time.Instant.EPOCH, "catalog-evidence");
  }

  private record ReadOnlyCopySchemas(java.util.Map<String,
      OperationSchemaMaps> maps)
      implements OperationSchemaLoader {
    @Override public OperationSchemaMaps load(String operationId) {
      return maps.get(operationId);
    }
    @Override public MappingSchemaSide persistRequest(
        String compilationId, String serviceCallId, String operationId, String contentType) {
      throw new UnsupportedOperationException("This fixture only reads catalog schemas");
    }
    @Override public MappingSchemaSide persistResponse(
        String compilationId, String serviceCallId, String operationId, String contentType, String responseCode) {
      throw new UnsupportedOperationException("This fixture only reads catalog schemas");
    }
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
        new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"), List.of(), "Hello world!",
            List.of(), List.of()), CONVERSATION);
    assertTrue(accepted.accepted());
    assertTrue(feedback.lastPlanFailure(CONVERSATION).isEmpty());
  }

  @Test
  void sourceOmissionWithoutBoundAuthorEvidenceCannotCreateBriefOrRepairAuthority() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);

    var result = tool.capture(new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"),
        List.of(), "Return Hello world!", List.of(), List.of(), List.of(
            new RequirementAnalysisCapture.SourceOmission("invented-source", "Invented requirement",
                "An unsupported omission."))), CONVERSATION);

    assertFalse(result.accepted());
    assertEquals("SOURCE_REVIEW_NOT_BOUND", result.issues().getFirst().code());
    assertEquals("REPAIR_CAPTURE", result.nextAction());
    assertTrue(candidate.get() == null);
    assertEquals(approved, ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow().approvedDraft());
    assertTrue(session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).isEmpty());
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
        "Greetings", List.of("GET /greetings"), List.of(), "Hello world!", List.of(), List.of());

    assertTrue(tool.capture(capture, CONVERSATION).accepted());
    ProductRequirementBriefTool.Result duplicate = tool.capture(capture, CONVERSATION);

    assertFalse(duplicate.accepted());
    assertEquals("STOP", duplicate.nextAction());
    assertEquals("DUPLICATE_CAPTURE", duplicate.issues().getFirst().code());
  }

  @Test
  void legacyAnalysisCopyCannotPublishAnApprovalCandidate() {
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
        new RequirementAnalysisCapture("Forward task", List.of("POST task input"), List.of(),
            "Rename name to title", List.of(), List.of(
                new RequirementAnalysisCapture.Mapping("http-in", "http-out",
                    List.of(new RequirementAnalysisCapture.Rule("name", "title", null)), null))),
        CONVERSATION);

    assertFalse(result.accepted(), result.toString());
    assertEquals("MAPPING_ORIGIN_REQUIRED", result.issues().getFirst().code());
    assertEquals(null, candidate.get());
    assertEquals(java.util.Optional.empty(), session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class));
    assertEquals(approved, ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow().approvedDraft());
  }

  @Test
  void legacyRetainedExpressionCannotPublishAnApprovalCandidate() {
    var base = approvedWithTypedMapping().authoredDraft();
    String expression = "Read the saved original onTaskStart processId.";
    var facts = base.facts().stream().map(fact -> fact.fieldMapping() == null ? fact
        : new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(),
            fact.text(), new RequirementCaptureInput.FieldMappingInput("http-in", null, "http-out", "$.processId", expression)))
        .toList();
    var input = new RequirementCaptureInput.DraftInput(base.flow(), facts, base.capabilities(), base.openQuestions(), base.settings());
    var approved = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", null);
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);
    var result = tool.capture(new RequirementAnalysisCapture("Forward task", List.of("POST task input"), List.of(),
        "Return the original process identifier", List.of(), List.of()), CONVERSATION);
    assertFalse(result.accepted(), result.toString());
    assertEquals("MAPPING_VALUE_UNSUPPORTED", result.issues().getFirst().code());
    assertEquals(null, candidate.get());
    assertEquals(java.util.Optional.empty(), session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class));
    assertEquals(expression, ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow().approvedDraft()
        .authoredDraft().facts().stream().filter(fact -> fact.fieldMapping() != null)
        .findFirst().orElseThrow().fieldMapping().expression());
  }

  @Test
  void controlledRejectionKeepsDraftAndRepairsOnNextCapture() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    tool = new ProductRequirementBriefTool(session, feedback, new ObjectMapper(),
        new E2eRecoveryFaultInjector("Create chain named", "requirement-analysis=CONTRACT_SHAPE:1"));
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION, approved, candidate::set);
    RequirementAnalysisCapture capture = new RequirementAnalysisCapture(
        "Greetings", List.of("GET /greetings"), List.of(), "Hello world!", List.of(), List.of());

    ProductRequirementBriefTool.Result first = tool.capture(capture, CONVERSATION);
    assertEquals("INJECTED_BRIEF_REJECTION", first.issues().getFirst().code());
    assertEquals("REPAIR_CAPTURE", first.nextAction());
    assertTrue(candidate.get() == null);
    assertEquals(approved, ProductCapabilityCaptureContext.binding(CONVERSATION)
        .orElseThrow().approvedDraft());

    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> tool.capture(capture, CONVERSATION));
    var corrected = new RequirementAnalysisCapture(
        "Greetings", List.of("GET /greetings"), List.of(), "Return Hello world!", List.of(), List.of());
    assertTrue(tool.capture(corrected, CONVERSATION).accepted());
    assertTrue(candidate.get() instanceof RequirementBrief);
  }

  @Test
  void rejectsMappingWithoutTargetFieldBeforePublishingBrief() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        RequirementFactFixtures.greetingsApprovedDraft(), candidate::set);

    ProductRequirementBriefTool.Result rejected = tool.capture(
        new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"), List.of(), "Hello world!",
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
        new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"), List.of(), "Hello world!",
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
        new RequirementAnalysisCapture("Forward task", List.of("POST task input"), List.of(),
            "Copy title to Subject", List.of(), List.of(
                new RequirementAnalysisCapture.Mapping("http-in", "http-out",
                    List.of(new RequirementAnalysisCapture.Rule("name", "Subject", null)), null))),
        CONVERSATION);

    assertFalse(result.accepted(), "The approved title field must not become name in the brief");
    assertTrue(candidate.get() == null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void legacyAuthoredCopyRequiresRecaptureWhetherAnalysisEchoesIt(boolean modelIncludesMapping) {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        approvedWithTypedMapping(), candidate::set);

    ProductRequirementBriefTool.Result result = tool.capture(
        new RequirementAnalysisCapture("Forward task", List.of("POST task input"), List.of(),
            "Copy title to Subject", List.of(), modelIncludesMapping
                ? List.of(new RequirementAnalysisCapture.Mapping("http-in", "http-out",
                    List.of(new RequirementAnalysisCapture.Rule("title", "Subject", null)), null))
                : List.of()), CONVERSATION);

    assertFalse(result.accepted(), result.toString());
    assertEquals("MAPPING_ORIGIN_REQUIRED", result.issues().getFirst().code());
    assertEquals(null, candidate.get());
    assertEquals(java.util.Optional.empty(), session.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class));
    assertEquals(null, ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow()
        .approvedDraft().authoredDraft().facts().stream().filter(fact -> fact.fieldMapping() != null)
        .findFirst().orElseThrow().fieldMapping().value());
  }

  @Test
  void historicalLegacyMappingKeepsCoverageThroughCanonicalEditsAndExplicitRename() {
    RequirementDraft approved = approvedWithTypedMapping();
    RequirementBrief brief = RequirementBriefProjector.project(new RequirementBrief("Forward task",
        List.of("POST task input"), List.of(), List.of(), List.of(), "Copy title to Subject")
        .withFacts(approved.facts()).withFlow(approved.flow())
        .withMappingIntents(RequirementBriefProjector.authoredMappings(approved)));
    var intent = brief.mappingIntents().getFirst();
    var validator = new RequirementBriefCoverageValidator();

    var supplement = MappingTurnProcessor.process(brief, "Add the source name", (current, message) ->
        MappingTurnResult.changes(new MappingTurnResult.AddRule(
            intent.mappingIntentId(), "\"salesforce\"", "sourceAppName", null)));

    assertTrue(supplement.applied());
    assertTrue(validator.validate(approved, supplement.brief()).isEmpty(),
        () -> validator.validate(approved, supplement.brief()).toString());
    assertEquals(List.of("mapping-title-subject"),
        supplement.brief().mappingIntents().getFirst().rules().getFirst().requirementIds());

    var renamed = MappingTurnProcessor.process(supplement.brief(), "Rename Subject to Title", (current, message) ->
        MappingTurnResult.changes(new MappingTurnResult.UpdateRule(
            intent.mappingIntentId(), "Subject", "", "Title", null)));

    assertTrue(renamed.applied());
    var renamedRule = renamed.brief().mappingIntents().getFirst().rules().getFirst();
    assertEquals("$.title", renamedRule.sourcePath());
    assertEquals("$.Title", renamedRule.targetPath());
    assertEquals(List.of("mapping-title-subject"), renamedRule.requirementIds());
    assertTrue(validator.validate(approved, renamed.brief()).isEmpty(),
        () -> validator.validate(approved, renamed.brief()).toString());
    assertTrue(validator.validate(approved, brief.withMappingIntents(List.of())).isPresent());
    var unapprovedRename = renamed.brief().withMappingIntents(List.of(
        intent.withRules(List.of(renamedRule.withStatus(
            org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus.PROPOSED)))));
    assertTrue(validator.validate(approved, unapprovedRename).isPresent());
  }

  @Test
  void pinsTheTargetFolderTheAuthorApproved() throws Exception {
    var brief = captureTypedBrief("$.title", typedSourceSchema(false),
        new RequirementCaptureInput.DraftSettings(null, null, "Integrations/Salesforce"));
    assertEquals("Integrations/Salesforce", brief.targetFolderPath());
  }

  private static RequirementDraft approvedWithTypedMapping() {
    return approvedWithTypedMapping(new RequirementCaptureInput.DraftSettings(null, null));
  }

  private static RequirementDraft approvedWithTypedMapping(
      RequirementCaptureInput.DraftSettings settings) {
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
        List.of(), settings);
    return RequirementCaptureProjection.toDraft(input, null, List.of(), "1", null);
  }
}
