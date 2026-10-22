package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.*;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture;
import org.qubership.integration.platform.ai.plan.MappingTurnInterpreter;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

class RequirementAmendmentRuntimeTest {
  @ParameterizedTest
  @CsvSource({"false, false, false, false", "true, false, false, false", "false, true, false, false",
      "false, true, true, false", "false, false, false, true"})
  void v2FactChangeReopensTheDraftAndRestoresTheExactCorrectionAfterRestart(
      boolean executionHalt, boolean planningMappingChange, boolean capturedAsChanges,
      boolean replacementReview) throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Clock clock = Clock.systemUTC();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    var runs = new ProductPipelineRunStore(blobs, mapper, clock);
    ProductPipelineProfile profile;
    try (var input = getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profile = ProductPipelineProfileParser.parse(input);
    }
    AtomicReference<StageExecutionContext> resumed = new AtomicReference<>();
    AtomicReference<String> producerInput = new AtomicReference<>();
    StageCapability discovery = new StageCapability() {
      public String capabilityId() { return "requirement-discovery"; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        resumed.set(context);
        if (replacementReview) {
          var drafts = new org.qubership.integration.platform.ai.plan.RequirementDraftStore();
          drafts.put(context.conversationId(),
              (org.qubership.integration.platform.ai.plan.RequirementDraft)
                  context.attributes().get("requirementCorrectionDraft"));
          return new org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability(
              null, drafts, (chat, text) -> {
                producerInput.set(text);
                return Multi.createFrom().empty();
              }, null).execute(context);
        }
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "review corrected draft")));
      }
    };
    var capabilities = new StageCapabilityRegistry(List.of(discovery));
    var narrative = new FailureNarrative(FakeFailureNarrativeAgent.owner("", "")
        .answeringUnder("REQUIREMENT_CHANGE", ""), 12, null);
    var builder = CreateChainTestOrchestrator.supportBuilder(runs, artifacts, capabilities, clock)
        .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile)))
        .failureNarrative(narrative);
    if (planningMappingChange) {
      builder.mappingTurnAdapter(new MappingTurnInterpreter((flow, intents, text) ->
          capturedAsChanges
              ? new MappingTurnCapture(MappingTurnCapture.Kind.CHANGES,
                  List.of(new MappingTurnCapture.IntentChange("greetings", "result",
                      List.of(new MappingIntentRule("", "error.code", "On failure, return the error code.")), null)),
                  List.of(), "", List.of())
              : new MappingTurnCapture(MappingTurnCapture.Kind.CLARIFICATION, List.of(), List.of(),
                  "FLOW_CHANGE", List.of())));
    }
    var support = builder.build();
    String runId = "amendment-v2", chatId = "amendment-chat";
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(),
        profile.profileVersion(), "profile-sha", "baseline", "baseline-sha",
        List.of(new DependencyClosureEntry("requirement-discovery", "1", "discovery-sha")),
        "closure-sha", new KnowledgePackageRef("knowledge", "1", "1.0.0", "checksum", "CERTIFIED", "certificate"),
        "24.4", List.of(new ArtifactTypeRef("user-input", 1)), null);
    var command = new StartOrResumeCommand(chatId, runId, profile, manifest);
    support.bootstrap(command, "flow");
    var draft = RequirementFactFixtures.greetingsApprovedDraft();
    if (replacementReview) {
      draft = RequirementCaptureProjection.toDraft(new RequirementCaptureInput.DraftInput(
          new RequirementCaptureInput.FlowInput(List.of(new RequirementCaptureInput.InteractionInput(
              "greetings", RequirementFlow.Direction.INBOUND, "Caller", "GET /greetings", null, null, null)), List.of()),
          List.of(new RequirementCaptureInput.FactInput("goal", List.of(),
              RequirementCaptureInput.FactKind.GOAL, RequirementCaptureInput.Polarity.POSITIVE, "Return Hello world!")),
          List.of(new RequirementCaptureInput.CapabilityInput("greetings", "http-trigger",
              RequirementCaptureInput.HttpMode.CUSTOM, RequirementCaptureInput.HttpMethod.GET,
              "/greetings", null, null, null)), List.of(), new RequirementCaptureInput.DraftSettings(false, null)),
          null, List.of(), null, null);
    }
    if (capturedAsChanges) {
      draft = draft.withFlow(new RequirementFlow(List.of(
          draft.flow().interactions().getFirst(),
          new RequirementFlow.Interaction("result", RequirementFlow.Direction.OUTBOUND,
              "Target", "deliverResult", "")),
          List.of(new RequirementFlow.Transition("greetings", "result"))));
    }
    var provenance = new ArtifactProvenance(runId, "requirement-discovery", profile.profileId(),
        profile.profileVersion(), "profile-sha", "requirement-discovery", "1", "closure-sha");
    Reference acceptedDraft = replacementReview ? artifacts.append(new AppendCommand(runId,
        Kind.REQUIREMENT_DRAFT, "2", "requirement-discovery", "1", draft.withIdsRequested(true),
        List.of(), null, provenance)).reference() : null;
    Reference draftRef = artifacts.append(new AppendCommand(runId, Kind.REQUIREMENT_DRAFT, "2",
        "requirement-discovery", "1", draft, List.of(), null, provenance)).reference();
    Reference hint = artifacts.append(new AppendCommand(runId, Kind.CATALOG_BINDING_HINT, "1",
        "requirement-discovery", "1", Map.of("hint", "companion"), List.of(draftRef), null, provenance)).reference();
    Reference bypass = artifacts.append(new AppendCommand(runId, Kind.IDS_BYPASS, "1",
        "requirement-discovery", "1", Map.of("bypass", true), List.of(draftRef), null, provenance)).reference();
    var brief = new RequirementBrief("Greetings", List.of("GET /greetings"), List.of(), List.of(),
        List.of(), "Return Hello world!", draftRef.artifactId(), draft.planningText(), draft.facts())
        .withFlow(draft.flow());
    Reference briefRef = artifacts.append(new AppendCommand(runId, Kind.REQUIREMENT_BRIEF, "1",
        "requirement-analysis", "1", brief, List.of(draftRef), null,
        new ArtifactProvenance(runId, "requirement-analysis", profile.profileId(), profile.profileVersion(),
            "profile-sha", "requirement-analysis", "1", "closure-sha"))).reference();
    Reference planRef = planningMappingChange ? artifacts.append(new AppendCommand(runId,
        Kind.IMPLEMENTATION_PLAN, "1", "design-planning", "1", new ImplementationPlan("Approved plan"),
        List.of(briefRef), null, new ArtifactProvenance(runId, "design-planning", profile.profileId(),
            profile.profileVersion(), "profile-sha", "design-planning", "1", "closure-sha"))).reference() : null;
    var doc = runs.load(runId).orElseThrow();
    if (planningMappingChange) {
      long priorRevision = doc.run().runRevision();
      var ledger = new RecoveryAttemptLedger();
      var key = ledger.key("requirement-discovery", RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
          "", doc.transitions());
      runs.commit(priorRevision, new LogicalCommit(runId, priorRevision, RunStatus.RUNNING,
          "requirement-discovery", doc.run().stages(),
          new StageAttempt("prior-revision", "requirement-discovery", priorRevision + 1,
              StageStatus.SUCCEEDED, clock.instant(), clock.instant(), List.of(draftRef), null),
          new RunTransition(priorRevision, priorRevision + 1, RunStatus.RUNNING, RunStatus.RUNNING,
              "requirement-discovery", clock.instant(),
              ledger.recordReopen(key, RecoveryAttemptLedger.ReopenInitiator.AUTHOR, ""))));
      doc = runs.load(runId).orElseThrow();
    }
    List<StageSnapshot> stages = new ArrayList<>();
    for (var stage : doc.run().stages()) {
      if (stage.stageId().equals("requirement-discovery")) {
        stages.add(replacementReview
            ? new StageSnapshot(stage.stageId(), StageStatus.SUCCEEDED, List.of(acceptedDraft), null,
                List.of(draftRef, hint, bypass), null, null)
            : new StageSnapshot(stage.stageId(), StageStatus.SUCCEEDED, List.of(draftRef, hint, bypass), null));
      } else if (stage.stageId().equals("requirement-analysis")) {
        stages.add(new StageSnapshot(stage.stageId(),
            executionHalt || planningMappingChange ? StageStatus.SUCCEEDED : StageStatus.WAITING_FOR_APPROVAL,
            executionHalt || planningMappingChange ? List.of(briefRef) : List.of(),
            executionHalt || planningMappingChange ? briefRef.artifactId() : null,
            List.of(briefRef), briefRef, 1));
      } else if (planningMappingChange && stage.stageId().equals("design-planning")) {
        stages.add(new StageSnapshot(stage.stageId(), StageStatus.WAITING_FOR_APPROVAL,
            List.of(), null, List.of(planRef), planRef, 1));
      } else if (executionHalt && stage.stageId().equals("design-execution")) {
        stages.add(new StageSnapshot(stage.stageId(), StageStatus.WAITING_FOR_INPUT, List.of(), null));
      } else { stages.add(stage); }
    }
    long revision = doc.run().runRevision();
    RunStatus waiting = executionHalt ? RunStatus.WAITING_FOR_INPUT : RunStatus.WAITING_FOR_APPROVAL;
    String waitingStage = executionHalt ? "design-execution"
        : planningMappingChange ? "design-planning" : "requirement-analysis";
    runs.commit(revision, new LogicalCommit(runId, revision, waiting,
        waitingStage, stages, new StageAttempt("review", waitingStage, revision + 1,
            executionHalt ? StageStatus.WAITING_FOR_INPUT : StageStatus.WAITING_FOR_APPROVAL,
            clock.instant(), clock.instant(), planningMappingChange ? List.of(planRef) : List.of(briefRef),
            replacementReview ? ProductPipelineRunSupport.haltEvidence(Map.of(
                ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, "requirement-discovery",
                ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Rejected mapping target",
                ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "MAPPING_CONTRACT",
                "mappingRepairAutomatic", "true"), null) : null),
        new RunTransition(revision, revision + 1, RunStatus.RUNNING, waiting,
            waitingStage, clock.instant(), executionHalt
                ? PipelineGates.tag(PipelineGates.RECOVERY_REGENERATE_EXECUTION, "Review the requirements")
                : planningMappingChange ? "Approve the plan" : "Approve the brief")));
    if (replacementReview) {
      support.restoreForExternalWorkflow(command).collect().asList().await().indefinitely();
      assertEquals("requirement-discovery",
          support.runAttributes(runId).get(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR));
    }
    var conversations = new ConversationService();
    String correction = planningMappingChange ? "On failure, include error.code and the error text in the response."
        : "Change the greeting to Good morning and remove the body logging requirement.";
    var source = conversations.archive().beginInput(chatId, correction, null);
    conversations.archive().resolveInput(chatId, source, correction);
    support.withConversations(conversations);
    var inputCommand = new AcceptInputCommand(runId, correction);
    support.recordInput(inputCommand).collect().asList().await().indefinitely();
    var reopened = runs.load(runId).orElseThrow();
    assertEquals("requirement-discovery", reopened.run().currentStageId());
    var analysis = reopened.run().stages().stream()
        .filter(stage -> stage.stageId().equals("requirement-analysis")).findFirst().orElseThrow();
    assertEquals(StageStatus.PENDING, analysis.status());
    assertEquals(null, analysis.approvableReference());
    assertTrue(analysis.candidateReferences().isEmpty());
    assertEquals(executionHalt ? List.of(briefRef) : List.of(), analysis.outputRefs());
    assertEquals(executionHalt || planningMappingChange ? 1 : 0, reopened.transitions().stream()
        .filter(transition -> transition.reason() != null
            && transition.reason().startsWith(RecoveryAttemptLedger.AUTHOR_REOPEN_REASON_PREFIX)).count());
    if (capturedAsChanges) {
      assertEquals(briefRef, artifacts.latest(runId, Kind.REQUIREMENT_BRIEF).orElseThrow().reference());
    }
    UserInput associated = artifacts.payload(artifacts.latest(runId, Kind.USER_INPUT).orElseThrow(), UserInput.class);
    assertEquals(correction, associated.text());
    assertEquals("requirement-discovery", associated.targetStageId());
    assertEquals(draftRef, associated.draftReference());
    assertEquals(List.of(source.turnId()), associated.sourceTurnIds());
    assertThrows(IllegalStateException.class, () -> support.recordApprove(new ApproveCommand(runId,
        briefRef, reopened.run().runRevision())).collect().asList().await().indefinitely());
    var restored = CreateChainTestOrchestrator.supportBuilder(runs, artifacts, capabilities, clock)
        .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile))).build();
    restored.restoreForExternalWorkflow(command).collect().asList().await().indefinitely();
    restored.stageExecutor().execute(runId, "requirement-discovery").await().indefinitely();
    assertEquals(correction, resumed.get().attributeAsString("userText"));
    assertEquals(draftRef, resumed.get().attributes().get("requirementCorrectionDraftReference"));
    if (replacementReview) {
      var repair = StageRepairEvidence.from(resumed.get());
      assertEquals("MAPPING_CONTRACT", repair.causeCode());
      assertEquals(correction + "\n\n<current-native-repair-evidence>\n"
          + "Current server validation for this repair turn; this is not an author message or amendment authority.\n"
          + repair.diagnosticText() + "\n</current-native-repair-evidence>\n", producerInput.get());
    }
  }
}
