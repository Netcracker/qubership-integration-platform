package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecRequirementRecoveryTest {
  private static final String RUN = "uploaded-spec-recovery";
  private static final String CHAT = "uploaded-spec-conversation";
  private static final String RESULT_QUESTION = "result-delivery";
  private static final String ANSWER = "Send results by POST to https://om.example/results. Preserve executionId. Proceed without IDS.";
  private static final String CATALOG_ANSWER = "Use OM onTaskResult as a catalog operation. Salesforce WFM createTask is imported. Preserve executionId. Proceed without IDS.";

  @org.junit.jupiter.api.AfterEach
  void releaseConversationBinding() {
    org.qubership.integration.platform.ai.chat.ToolSession.clear();
    ProductCapabilityCaptureContext.unbind(CHAT);
  }

  @Test
  void importedSalesforceDoesNotSendAnUnboundOmOperationBackToImportAfterReload() throws Exception {
    var h = new Harness();
    h.runtime.support().bootstrap(new StartOrResumeCommand(CHAT, RUN, h.profile, h.manifest), "test-flow");
    String seed = "Create POST /tasks, call Salesforce WFM createTask, then return OM task results. Proceed without IDS.";
    var source = h.source(seed);
    h.artifacts.append(new CompilationArtifacts.AppendCommand(RUN, Kind.USER_INPUT, "1", "test", "1",
        new UserInput(source.turnId(), "requirement-discovery", seed, h.clock.instant(), null,
            List.of(source.turnId())), List.of(), null,
        new ArtifactProvenance(RUN, "requirement-discovery", h.profile.profileId(),
            h.profile.profileVersion(), "", "test", "1", "")));
    h.step();
    h.step();
    var approval = h.approval.appendApprovalRecord(RUN, CHAT, h.approval.createDecision(CHAT), h.artifacts);
    h.runtime.recordInput(new AcceptInputCommand(RUN, ChatEvent.IMPORT_ACTION)).collect().asList().await().indefinitely();
    assertInstanceOf(StageDecision.ReopenProducer.class, h.step().decision());
    h.partialCatalogDraft = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", null, null, null),
        new InteractionInput("salesforce", Direction.OUTBOUND, "Salesforce WFM", "createTask", null, null, null),
        new InteractionInput("result", Direction.OUTBOUND, "OM", "onTaskResult", null, null, null)),
        List.of(new TransitionInput("entry", "salesforce"), new TransitionInput("salesforce", "result"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Preserve executionId in task results.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/tasks", null, null, null)), List.of(), new DraftSettings(false, null));
    var binding = new CatalogBindingHint("3", "salesforce", "salesforce", "POST /sobjects/Task",
        "salesforce", "group", "spec", "createTask", "http", "POST", "/sobjects/Task", "1", h.clock.instant(), "catalog-read");
    h.capturedBindings = List.of(binding);
    h.step();
    assertEquals(RunStatus.WAITING_FOR_INPUT, h.run().run().status());
    h.reload();
    h.source(CATALOG_ANSWER);
    h.runtime.recordInput(new AcceptInputCommand(RUN, CATALOG_ANSWER)).collect().asList().await().indefinitely();
    assertInstanceOf(StageDecision.Retry.class, h.step().decision());

    assertEquals("requirement-discovery", h.run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, h.run().run().status());
    assertEquals(1, h.continuation.pendingWork().size());
    assertEquals("OPERATION_LOOKUP_REQUIRED", h.continuation.pendingWork().getFirst().code());
    assertEquals("result", h.continuation.pendingWork().getFirst().interactionId());
    assertFalse(h.continuation.accepted());
    h.reload();
    h.step();
    assertEquals("requirement-discovery", h.run().run().currentStageId());
    assertEquals("spec", h.drafts.get(CHAT).orElseThrow().catalogBinding("salesforce").orElseThrow().specificationId());
    assertTrue(h.artifacts.get(RUN, approval).isPresent());
    verify(h.gateway, times(1)).importUploadedSpec(any(), any(), any());
  }

  @Test
  void returnFromASavedImportHaltIgnoresOpenApiAndRetainsApprovalAcrossReload() throws Exception {
    var h = new Harness();
    h.runtime.support().bootstrap(new StartOrResumeCommand(CHAT, RUN, h.profile, h.manifest), "test-flow");
    String seed = "Create POST /tasks and return task results. Proceed without IDS.";
    var input = h.source(seed);
    h.artifacts.append(new CompilationArtifacts.AppendCommand(RUN, Kind.USER_INPUT, "1", "test", "1",
        new UserInput(input.turnId(), "requirement-discovery", seed, h.clock.instant(), null,
            List.of(input.turnId())), List.of(), null,
        new ArtifactProvenance(RUN, "requirement-discovery", h.profile.profileId(),
            h.profile.profileVersion(), "", "test", "1", "")));
    h.step();
    var approval = h.approval.appendApprovalRecord(RUN, CHAT, h.approval.createDecision(CHAT), h.artifacts);
    var current = h.run().run();
    var now = h.clock.instant();
    String prompt = PipelineGates.retag(PipelineGates.RECOVERY_REPEATED, "The requirement draft is incomplete.");
    String evidence = h.mapper.writeValueAsString(Map.of(
        ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "uploaded-spec-import",
        ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, StageOutcomeClass.MISSING_MANDATORY_INPUT.name()));
    h.runs.commit(current.runRevision(), LogicalCommit.statusChange(current, StageStatus.WAITING_FOR_INPUT,
        current.stages(), new StageAttempt("legacy-import-halt", current.currentStageId(), current.runRevision() + 1,
            StageStatus.WAITING_FOR_INPUT, now, now, List.of(), evidence),
        new RunTransition(current.runRevision(), current.runRevision() + 1, current.status(),
            RunStatus.WAITING_FOR_INPUT, current.currentStageId(), now, prompt)));
    h.reload();
    var budget = h.run().run().workRepairBudget();
    String command = "Go back to requirement-discovery and continue the original request. Do not deploy."
        + "\n\n---\n\n- `salesforce.json` (inlined)\n\n"
        + "{\"openapi\":\"3.0.3\",\"info\":{\"description\":\"Salesforce specification\"}}";
    h.source(command);
    h.runtime.recordInput(new AcceptInputCommand(RUN, command)).collect().asList().await().indefinitely();

    assertEquals("requirement-discovery", h.run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, h.run().run().status());
    assertEquals(budget, h.run().run().workRepairBudget());
    assertTrue(h.artifacts.get(RUN, approval).isPresent());
    h.reload();
    h.step();
    assertEquals(RunStatus.WAITING_FOR_INPUT, h.run().run().status());
    h.source(ANSWER);
    h.runtime.recordInput(new AcceptInputCommand(RUN, ANSWER)).collect().asList().await().indefinitely();
    h.step();
    assertTrue(h.drafts.get(CHAT).orElseThrow().readyForPlan());
    h.step();
    h.step();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, h.run().run().status());
    assertEquals(RUN, h.run().run().runId());
    assertTrue(h.artifacts.get(RUN, approval).isPresent());
    verify(h.gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void importedDraftReturnsToDiscoveryAndAdmitsClarificationAcrossReload(boolean reload) throws Exception {
    var h = new Harness();
    h.runtime.support().bootstrap(new StartOrResumeCommand(CHAT, RUN, h.profile, h.manifest), "test-flow");
    String seed = "Create POST /tasks and return task results. Proceed without IDS.";
    var initial = h.source(seed);
    h.artifacts.append(new CompilationArtifacts.AppendCommand(RUN, Kind.USER_INPUT, "1", "test", "1",
        new UserInput(initial.turnId(), "requirement-discovery", seed, h.clock.instant(), null,
            List.of(initial.turnId())), List.of(), null,
        new ArtifactProvenance(RUN, "requirement-discovery", h.profile.profileId(),
            h.profile.profileVersion(), "", "test", "1", "")));
    h.step();
    assertEquals("uploaded-spec-import", h.run().run().currentStageId());

    h.step();
    assertEquals(RunStatus.WAITING_FOR_INPUT, h.run().run().status());
    verify(h.gateway, never()).importUploadedSpec(any(), any(), any());
    var approval = h.approval.appendApprovalRecord(RUN, CHAT, h.approval.createDecision(CHAT), h.artifacts);
    h.runtime.recordInput(new AcceptInputCommand(RUN, ChatEvent.IMPORT_ACTION)).collect().asList().await().indefinitely();
    var imported = h.step();

    assertEquals("requirement-discovery",
        assertInstanceOf(StageDecision.ReopenProducer.class, imported.decision()).producerStageId());
    assertTrue(h.run().attempts().stream().anyMatch(attempt -> attempt.failureEvidence() != null
        && attempt.failureEvidence().contains("Uploaded specifications were imported")));
    verify(h.gateway).importUploadedSpec(eq(CHAT), any(), eq("INTERNAL"));
    if (reload) h.reload();
    h.step();
    assertEquals("requirement-discovery", h.run().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, h.run().run().status());
    var partial = h.drafts.get(CHAT).orElseThrow();
    assertEquals(RESULT_QUESTION, partial.authoredDraft().openQuestions().getFirst().questionId());
    if (reload) h.reload();
    var input = h.source(ANSWER);
    h.runtime.recordInput(new AcceptInputCommand(RUN, ANSWER)).collect().asList().await().indefinitely();
    h.step();
    var ready = h.drafts.get(CHAT).orElseThrow();
    assertTrue(ready.readyForPlan());
    assertEquals(partial.authoredDraft().facts(), ready.authoredDraft().facts());
    assertEquals(partial.flow(), ready.flow());
    assertTrue(ready.openQuestions().isEmpty());
    assertNotNull(h.archive.read(CHAT).turns().stream().filter(turn -> turn.id().equals(input.turnId()))
        .findFirst().orElseThrow().consumedDraftReference());
    h.step();
    h.step();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, h.run().run().status());
    assertEquals(RUN, h.run().run().runId());
    assertTrue(h.artifacts.get(RUN, approval).isPresent());
    h.reload();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, h.run().run().status());
    var selected = h.run().run().stages().stream()
        .filter(stage -> stage.stageId().equals("requirement-analysis")).findFirst().orElseThrow();
    h.runtime.recordApprove(new ApproveCommand(RUN, selected.approvableReference(), h.run().run().runRevision()))
        .collect().asList().await().indefinitely();
    assertEquals(RunStatus.PLAN_APPROVED, h.run().run().status());
    verify(h.gateway, times(1)).importUploadedSpec(any(), any(), any());
  }

  private static final class Harness {
    final Clock clock = Clock.systemUTC();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts storage = new CompilationArtifacts(blobs, mapper, clock);
    final ProductPipelineArtifactStore artifacts = new ProductPipelineArtifactStore(storage);
    final ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, mapper, clock);
    final ConversationArchive archive = new ConversationArchive(blobs, mapper);
    final ConversationService conversations = mock(ConversationService.class);
    final CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    final S3Service s3 = mock(S3Service.class);
    List<CatalogBindingHint> capturedBindings = List.of();
    final RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class, call ->
        "canonicalBindings".equals(call.getMethod().getName()) ? capturedBindings
            : org.mockito.Answers.RETURNS_DEFAULTS.answer(call));
    final UploadedSpecsApprovalHandler approval = new UploadedSpecsApprovalHandler(conversations, s3);
    final AtomicInteger discoveries = new AtomicInteger();
    final ProductPipelineProfile profile;
    final RunManifest manifest;
    RequirementDraftStore drafts;
    CreateChainTestOrchestrator runtime;
    DraftInput partialCatalogDraft;
    RequirementCaptureResult continuation;

    Harness() throws Exception {
      when(conversations.archive()).thenReturn(archive);
      when(conversations.getAllowedAttachmentKeys(CHAT)).thenReturn(List.of("uploads/salesforce.json"));
      when(s3.readObjectBytes(anyString())).thenReturn("""
          {"openapi":"3.0.3","info":{"title":"Salesforce WFM","version":"1"},
           "paths":{"/sobjects/Task":{"post":{"operationId":"createTask"}}}}
          """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      when(gateway.importUploadedSpec(eq(CHAT), any(), any())).thenReturn(Uni.createFrom().item(
          new UploadedSpecImportOutcome("uploads/salesforce.json", "salesforce", "group", "spec", true)));
      var draft = new ArtifactTypeRef("requirement-draft", 2);
      var brief = new ArtifactTypeRef("requirement-brief", 1);
      ProductPipelineProfile production;
      try (var source = getClass().getClassLoader().getResourceAsStream("product-pipelines/profiles/create-chain-v2.yaml")) {
        production = ProductPipelineProfileParser.parse(source);
      }
      profile = new ProductPipelineProfile(1, "uploaded-spec-recovery", "1", production.runInputs(), List.of(
          production.stages().stream().filter(stage -> stage.stageId().equals("requirement-discovery")).findFirst().orElseThrow(),
          production.stages().stream().filter(stage -> stage.stageId().equals("uploaded-spec-import")).findFirst().orElseThrow(),
          new ProfileStage("requirement-analysis", "analysis", List.of(draft), List.of(brief),
              new ApprovalPolicy(brief), null, new RetryPolicy(0, 0))),
          new TerminalPolicy("requirement-analysis", "PLAN_APPROVED"), List.of());
      manifest = new RunManifest(RUN, null, List.of(), "product", profile.profileId(), "1", "sha",
          "baseline", "sha", List.of(), "sha", null, "24.4", List.of(), null);
      reload();
    }

    void reload() {
      drafts = new RequirementDraftStore(storage, new CompilationSessions(blobs, mapper, clock), conversations);
      var feedback = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore());
      var tools = new RequirementCaptureTools(drafts, bindingAdapter, mapper,
          new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback), feedback, null, null,
          new UploadedSpecOperations(conversations, s3), conversations);
      var gather = new RequirementDiscoveryCapability(null, drafts, null, (chat, text) -> {
        try {
          if (partialCatalogDraft != null && text.contains(CATALOG_ANSWER)) {
            var questions = drafts.get(chat).orElseThrow().authoredDraft().openQuestions().stream()
                .map(QuestionInput::questionId).toList();
            var captured = tools.updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), questions, null));
            assertTrue(mapper.readTree(captured).path("accepted").asBoolean(), captured);
            continuation = mapper.readValue(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
                RequirementCaptureResult.class);
          } else if (!text.contains(ANSWER)) {
            var waiting = partialCatalogDraft != null ? partialCatalogDraft : new DraftInput(new FlowInput(List.of(
                new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", "Receive tasks", null, null),
                new InteractionInput("result", Direction.OUTBOUND, "OM", "Return task results", "Preserve executionId", null, null)),
                List.of(new TransitionInput("entry", "result"))),
                List.of(new FactInput("result-behavior", List.of("result"), FactKind.BEHAVIOR,
                    Polarity.POSITIVE, "Preserve executionId in task results.")),
                List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
                    "/tasks", null, null, null)), List.of(), new DraftSettings(null, null));
            var captured = tools.captureRequirementDraft(new DraftInput(waiting.flow(), waiting.facts(),
                waiting.capabilities(), List.of(new QuestionInput(RESULT_QUESTION, List.of("result"),
                    "How should task results be returned to OM?",
                    new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence(
                        "result", "path", org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.MISSING_VALUE,
                        conversations.archive().read(chat).activeTurns().stream().filter(turn ->
                            turn.message().role() == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
                                && "READY".equals(turn.status())).map(ConversationArchive.Turn::id).toList()))), waiting.settings()));
            assertTrue(mapper.readTree(captured).path("accepted").asBoolean(), captured);
            assertTrue(mapper.readTree(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY))
                .path("accepted").asBoolean());
          } else {
            var captured = tools.updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CapabilityInput("result", "http-sender", null,
                    HttpMethod.POST, "https://om.example/results", null, null, null)), List.of(),
                List.of(), List.of(), List.of(RESULT_QUESTION), new DraftSettings(false, null)));
            assertTrue(mapper.readTree(captured).path("accepted").asBoolean(), captured);
            var finished = tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE);
            assertTrue(mapper.readTree(finished).path("accepted").asBoolean(), finished);
          }
        } catch (Exception error) {
          throw new IllegalStateException(error);
        }
        return Multi.createFrom().empty();
      }, conversations);
      gather.uploadedSpecs = new UploadedSpecOperations(conversations, s3);
      StageCapability discovery = new StageCapability() {
        public String capabilityId() { return "requirement-discovery"; }
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          if (discoveries.incrementAndGet() > 1) return gather.execute(context);
          drafts.beginTurn(CHAT);
          var legacy = new RequirementDraft(true, "Return task results", DraftDecision.READY_FOR_PLAN,
              List.of(), "brainstorming", "1").withFlow(new RequirementFlow(List.of(
                  new RequirementFlow.Interaction("entry", Direction.INBOUND, "Caller", "POST /tasks", ""),
                  new RequirementFlow.Interaction("result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                  List.of(new RequirementFlow.Transition("entry", "result"))))
              .withFacts(List.of(RequirementFactFixtures.httpTriggerFact("entry", "POST", "/tasks")))
              .withIdsRequested(false);
          drafts.put(CHAT, legacy);
          return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED,
              List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, legacy, context.inputRefs())), "Captured", null)));
        }
      };
      var importer = new AutoUploadedSpecImportCapability(gateway, conversations, artifacts, approval,
          mock(CatalogBindingMatcher.class), drafts);
      StageCapability analysis = new StageCapability() {
        public String capabilityId() { return "analysis"; }
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          var draft = drafts.get(CHAT).orElseThrow();
          var ref = artifacts.latest(RUN, Kind.REQUIREMENT_DRAFT).orElseThrow().reference();
          var brief = new RequirementBrief("Task results", List.of(), List.of(), List.of(), List.of(), "Task endpoint",
              ref.artifactId(), draft.planningText(), draft.facts()).withFlow(draft.flow());
          return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.CANDIDATE,
              List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, context.inputRefs())), "Ready for approval", null)));
        }
      };
      var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts,
          new StageCapabilityRegistry(List.of(discovery, importer, analysis)), clock)
          .failureNarrative(new FailureNarrative(FakeFailureNarrativeAgent.owner("Requirement draft is incomplete.",
              "requirement-discovery"))).build();
      runtime = new CreateChainTestOrchestrator(support, runs);
      ((ProductPipelineStageExecutor) runtime.stageExecutor()).withConversations(conversations);
      if (runs.load(RUN).isPresent()) {
        runtime.restoreForExternalWorkflow(new StartOrResumeCommand(CHAT, RUN, profile, manifest))
            .collect().asList().await().indefinitely();
      }
    }

    ConversationArchive.Input source(String text) {
      var input = archive.beginInput(CHAT, text, null);
      archive.resolveInput(CHAT, input, text);
      return input;
    }

    StageExecutionResult step() {
      var result = runtime.stageExecutor().execute(RUN, run().run().currentStageId()).await().indefinitely();
      runtime.support().applyStageLifecycle(RUN, result).collect().asList().await().indefinitely();
      return result;
    }

    ProductPipelineRunDocument run() { return runs.load(RUN).orElseThrow(); }
  }
}
