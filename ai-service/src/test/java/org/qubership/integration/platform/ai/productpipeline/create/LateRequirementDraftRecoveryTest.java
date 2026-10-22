package org.qubership.integration.platform.ai.productpipeline.create;

import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.recovery.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class LateRequirementDraftRecoveryTest {
  private static final String RUN = "late-draft-recovery";
  private static final String CHAT = "late-draft-conversation";
  private static final String CORRECT = "Preserve request IDs.";
  private static final String WRONG = "Drop request IDs.";
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @ParameterizedTest
  @ValueSource(strings = {"requirement-analysis", "design-input", "design-execution"})
  void draftFaultRepairsItsProducerAndContinuesTheSameRun(String observingStage) throws Exception {
    var h = new Harness(observingStage, false, false);
    h.complete();

    assertEquals(List.of("requirement-discovery"), h.reopened);
    assertEquals(1, h.draftRepairs.get());
    assertEquals(draft(CORRECT, RequirementPolicyConflict.Decision.KEEP_REQUESTED),
        h.drafts.get(CHAT).orElseThrow().authoredDraft());
    assertEquals(2, h.archive.read(CHAT).turns().size());
    assertEquals(RUN, h.run().run().runId());
    assertEquals(RunStatus.PLAN_APPROVED, h.run().run().status());
    assertEquals(observingStage.equals("requirement-analysis") ? 1 : 2, h.approvals);
  }

  @ParameterizedTest
  @ValueSource(strings = {"requirement-analysis", "design-input", "design-execution"})
  void briefOnlyFaultKeepsTheAcceptedDraft(String observingStage) throws Exception {
    var h = new Harness(observingStage, true, false);
    var selected = h.drafts.latestRevision(CHAT).orElseThrow().reference();
    h.complete();

    assertEquals(0, h.draftRepairs.get());
    assertFalse(h.reopened.contains("requirement-discovery"), h.reopened.toString());
    assertEquals(selected, h.drafts.latestRevision(CHAT).orElseThrow().reference());
    assertEquals(RunStatus.PLAN_APPROVED, h.run().run().status());
  }

  @Test
  void inputFaultBeforeBriefCaptureReopensDiscovery() throws Exception {
    var h = new Harness("requirement-analysis", false, false, true, false);
    h.complete();

    assertEquals(List.of("requirement-discovery"), h.reopened);
    assertEquals(1, h.draftRepairs.get());
  }

  @Test
  void mixedDraftAndBriefFindingsGrantOnlyDraftFieldAuthority() throws Exception {
    var h = new Harness("design-input", false, false, false, true);
    h.complete();

    assertEquals(List.of("requirement-discovery"), h.reopened);
    assertEquals(draft(CORRECT, RequirementPolicyConflict.Decision.KEEP_REQUESTED),
        h.drafts.get(CHAT).orElseThrow().authoredDraft());
  }

  @Test
  void rejectedSemanticCandidateCanBeReadDuringDiagnosis() throws Exception {
    var h = new Harness("design-input", true, true);
    h.complete();

    assertEquals(1, h.diagnoses.get());
    assertTrue(h.rejectedSemanticRead);
    assertEquals(List.of("requirement-analysis"), h.reopened);
    assertEquals(0, h.draftRepairs.get());
  }

  private static DraftInput draft(String text, RequirementPolicyConflict.Decision decision) {
    return withNativeAnchors(new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", null, null, null)), List.of()),
        List.of(new FactInput("retain-id", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, text),
            new FactInput("sibling", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Preserve all inputs."),
            new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
                "Requested NONE; company policy recommends RBAC.", null,
                new RequirementPolicyConflict("security", "accessControlType", "NONE", "RBAC", "choice", decision))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/tasks", null, null, null)),
        decision == null ? List.of(new QuestionInput("choice", List.of("entry"), "Keep NONE or use RBAC?")) : List.of(),
        new DraftSettings(false, null)));
  }

  private static DraftUpdate edit(List<FactInput> facts, List<String> removeQuestions) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), facts,
        List.of(), List.of(), List.of(), List.of(), List.of(), removeQuestions, null);
  }

  private static final class Harness {
    final Clock clock = Clock.systemUTC();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts storage = new CompilationArtifacts(blobs, JSON, clock);
    final ProductPipelineArtifactStore artifacts = new ProductPipelineArtifactStore(storage);
    final ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, JSON, clock);
    final ConversationArchive archive = new ConversationArchive(blobs, JSON);
    final ConversationService conversations = mock(ConversationService.class);
    final RequirementDraftStore drafts;
    final RequirementCaptureTools tools;
    final CreateChainTestOrchestrator runtime;
    final List<String> reopened = new ArrayList<>();
    final AtomicInteger draftRepairs = new AtomicInteger();
    final AtomicInteger analyses = new AtomicInteger();
    final AtomicInteger diagnoses = new AtomicInteger();
    int approvals;
    boolean rejectedSemanticRead;

    Harness(String observingStage, boolean briefOnly, boolean rejectedCandidate) throws Exception {
      this(observingStage, briefOnly, rejectedCandidate, false, false);
    }

    Harness(String observingStage, boolean briefOnly, boolean rejectedCandidate,
        boolean omitBriefCandidate, boolean mixedFindings) throws Exception {
      when(conversations.archive()).thenReturn(archive);
      var sessions = new CompilationSessions(blobs, JSON, clock);
      drafts = new RequirementDraftStore(storage, sessions, conversations);
      tools = new RequirementCaptureTools(drafts, mock(RequirementDraftTool.class), JSON);
      source("Create POST /tasks. Preserve request IDs and all inputs. Use NONE access control. Proceed without IDS.");
      String initialText = briefOnly ? CORRECT : WRONG;
      try (var ignored = ToolSession.open(CHAT)) {
        accepted(tools.captureRequirementDraft(draft(initialText, null)));
        var choices = new PolicyChoiceSelection(storage, sessions, conversations);
        var offered = choices.pending(CHAT, 7).orElseThrow();
        var command = new ChatDecisionCommand();
        command.setAction(ChatEvent.KEEP_REQUESTED_ACTION);
        command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
        command.setArtifactHash(offered.hash());
        command.setRevision(7);
        String marker = ChatDecisionService.transcriptMarker(command);
        var selectedSource = archive.beginInput(CHAT, marker, JSON.valueToTree(command));
        archive.resolveInput(CHAT, selectedSource, marker, JSON.valueToTree(command));
        drafts.beginTurn(CHAT, marker);
        assertTrue(choices.confirm(CHAT, command, 7).isPresent());
        accepted(tools.updateRequirementDraft(edit(List.of(
            draft(initialText, RequirementPolicyConflict.Decision.KEEP_REQUESTED).facts().get(2)), List.of("choice"))));
      }
      var discovery = new RequirementDiscoveryCapability(null, drafts, null, (chat, text) -> {
        try {
          var binding = ProductCapabilityCaptureContext.binding(chat).orElseThrow();
          assertEquals(Set.of("/facts/retain-id/text"), binding.requirementRepairPaths());
          draftRepairs.incrementAndGet();
          var collateral = edit(List.of(draft(CORRECT, null).facts().getFirst(),
              new FactInput("sibling", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Drop inputs.")), List.of());
          assertFalse(JSON.readTree(tools.updateRequirementDraft(collateral)).path("accepted").asBoolean());
          accepted(tools.updateRequirementDraft(edit(List.of(draft(CORRECT, null).facts().getFirst()), List.of())));
          accepted(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE));
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
        return Multi.createFrom().empty();
      }, conversations);
      var analysis = capability("analysis-cap", context -> {
        int attempt = analyses.incrementAndGet();
        var selected = drafts.get(CHAT).orElseThrow();
        var ref = artifacts.latest(RUN, Kind.REQUIREMENT_DRAFT).orElseThrow().reference();
        var brief = new RequirementBrief("Create tasks", List.of(), List.of(), List.of(), List.of(), "Task endpoint",
            ref.artifactId(), selected.planningText(), selected.facts()).withFlow(selected.flow());
        if ((briefOnly || mixedFindings) && attempt == 1) {
          ObjectNode changed = JSON.valueToTree(brief);
          for (var fact : changed.path("facts")) {
            if (fact.path("sourceFactId").asText().equals(mixedFindings ? "sibling" : "retain-id")) {
              ((ObjectNode) fact).put("text", WRONG);
            }
          }
          brief = JSON.convertValue(changed, RequirementBrief.class);
        }
        if (observingStage.equals("requirement-analysis") && invalid(brief)) {
          return rejected(omitBriefCandidate ? List.of()
              : List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())));
        }
        return outcome(StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())), null);
      });
      var design = capability("design-input-cap", context -> {
        var candidate = new ArtifactCandidate(Kind.CHAIN_SEMANTIC_REVISION, SemanticFixtures.linearOrders(), List.of());
        if (observingStage.equals("design-input") && invalid((RequirementBrief) context.attributes().get("requirementBrief"))) {
          var findings = new ArrayList<>(literalCause().findings());
          if (mixedFindings) findings.add(new PlanValidationFinding("SOURCE_LITERAL_MISMATCH",
              "The saved source requires preserving all inputs.", true, MappingValidationDetails.empty(),
              "sibling", "/facts[]/text"));
          return outcome(StageOutcomeClass.CONTRACT_FAILURE, rejectedCandidate ? List.of(candidate) : List.of(),
              new RecoveryCause(RecoveryCauseCode.BRIEF_CONTRACT, findings, ""));
        }
        return outcome(StageOutcomeClass.SUCCEEDED, List.of(candidate), null);
      });
      var execution = capability("execution-cap", context ->
          invalid((RequirementBrief) context.attributes().get("requirementBrief"))
              ? rejected(List.of()) : outcome(StageOutcomeClass.SUCCEEDED, List.of(), null));
      var agent = mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
      when(agent.recover(anyString(), anyString(),
        org.mockito.ArgumentMatchers.any(ChatRequestParameters.class))).thenAnswer(invocation -> {
        diagnoses.incrementAndGet();
        var context = JSON.readTree((String) invocation.getArgument(1));
        rejectedSemanticRead = context.path("rejectedArtifact").has("nodes");
        var evidence = context.path("evidence");
        var briefRef = JSON.treeToValue(evidence.path("approvedBriefRef"), CompilationArtifacts.Reference.class);
        if (briefRef == null) {
          for (var ref : evidence.path("rejectedArtifactRefs")) {
            if (ref.path("kind").asText().equals("REQUIREMENT_BRIEF")) {
              briefRef = JSON.treeToValue(ref, CompilationArtifacts.Reference.class);
            }
          }
        }
        return new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
            List.of(evidence.path("failureId").asText(), context.path("authorInputs").get(0).path("inputId").asText()),
            RecoveryAction.REVISE_BRIEF,
            List.of(new ProposedBriefChange("retain-id", "text", WRONG, CORRECT, "SOURCE_LITERAL_MISMATCH", false)),
            "", "Restore the requirement stated in the saved source.");
      });
      var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts,
          new StageCapabilityRegistry(List.of(discovery, analysis, design, execution)), clock)
          .failureNarrative(new FailureNarrative(agent)).build();
      runtime = new CreateChainTestOrchestrator(support, runs);
      ((ProductPipelineStageExecutor) runtime.stageExecutor()).withConversations(conversations);
      var draft = new ArtifactTypeRef("requirement-draft", 2);
      var brief = new ArtifactTypeRef("requirement-brief", 1);
      var semantic = new ArtifactTypeRef("chain-semantic-revision", 1);
      var profile = new ProductPipelineProfile(1, "late-draft-recovery", "1", List.of(), List.of(
          new ProfileStage("requirement-discovery", discovery.capabilityId(), List.of(), List.of(draft), null, null, new RetryPolicy(0, 0)),
          new ProfileStage("requirement-analysis", analysis.capabilityId(), List.of(draft), List.of(brief), new ApprovalPolicy(brief), null, new RetryPolicy(0, 0)),
          new ProfileStage("design-input", design.capabilityId(), List.of(brief), List.of(semantic), null, null, new RetryPolicy(0, 0)),
          new ProfileStage("design-execution", execution.capabilityId(), List.of(semantic), List.of(), null, null, new RetryPolicy(0, 0))),
          new TerminalPolicy("design-execution", "PLAN_APPROVED"), List.of());
      var manifest = new RunManifest(RUN, null, List.of(), "product", profile.profileId(), "1", "sha",
          "baseline", "sha", List.of(), "sha", null, "24.4", List.of(), null);
      runtime.startOrResume(new StartOrResumeCommand(CHAT, RUN, profile, manifest)).collect().asList().await().indefinitely();
      runtime.recordInput(new AcceptInputCommand(RUN, ChatEvent.CONTINUE_TO_PLANNING_ACTION)).collect().asList().await().indefinitely();
    }

    void complete() {
      for (int step = 0; step < 14 && run().run().status() != RunStatus.PLAN_APPROVED; step++) {
        String current = run().run().currentStageId();
        if (run().run().status() == RunStatus.WAITING_FOR_APPROVAL) {
          var snapshot = run().run().stages().stream().filter(stage -> current.equals(stage.stageId())).findFirst().orElseThrow();
          approvals++;
          runtime.recordApprove(new ApproveCommand(RUN, snapshot.approvableReference(), run().run().runRevision()))
              .collect().asList().await().indefinitely();
        } else {
          var result = runtime.stageExecutor().execute(RUN, current).await().indefinitely();
          if (result.decision() instanceof StageDecision.ReopenProducer reopen) reopened.add(reopen.producerStageId());
          assertFalse(result.decision() instanceof StageDecision.WaitForInput, result.decision().toString());
          assertFalse(result.decision() instanceof StageDecision.Fail, result.decision().toString());
          runtime.support().applyStageLifecycle(RUN, result).collect().asList().await().indefinitely();
        }
      }
      assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
    }

    ProductPipelineRunDocument run() { return runs.load(RUN).orElseThrow(); }

    private void source(String text) {
      var input = archive.beginInput(CHAT, text, null);
      archive.resolveInput(CHAT, input, text);
      drafts.beginTurn(CHAT, text);
    }

    private static boolean invalid(RequirementBrief brief) {
      return brief.facts().stream().anyMatch(fact -> fact.sourceFactId().equals("retain-id") && fact.text().equals(WRONG));
    }

    private static Multi<CapabilitySignal> rejected(List<ArtifactCandidate> candidates) {
      return outcome(StageOutcomeClass.CONTRACT_FAILURE, candidates, literalCause());
    }

    private static RecoveryCause literalCause() {
      return new RecoveryCause(RecoveryCauseCode.BRIEF_CONTRACT,
          List.of(new PlanValidationFinding("SOURCE_LITERAL_MISMATCH", "The saved source requires preserving request IDs.",
              true, MappingValidationDetails.empty(), "retain-id", "/facts[]/text")), "");
    }

    private static Multi<CapabilitySignal> outcome(StageOutcomeClass status, List<ArtifactCandidate> candidates, RecoveryCause cause) {
      return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(status, candidates,
          cause == null ? "accepted" : "Accepted requirement contradicts the saved source.", null, cause)));
    }

    private static StageCapability capability(String id, Function<StageExecutionContext, Multi<CapabilitySignal>> body) {
      return new StageCapability() {
        public String capabilityId() { return id; }
        public Multi<CapabilitySignal> execute(StageExecutionContext context) { return body.apply(context); }
      };
    }

    private static void accepted(String result) throws Exception {
      assertTrue(JSON.readTree(result).path("accepted").asBoolean(), result);
    }
  }
}
