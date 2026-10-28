package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class NativeDiscoveryBeforeDiagnosisTest {
  private static final String STAGE = "requirement-discovery";
  private static final String SCRIPT = "exchange.message.body = 'receipt-confirmed'";
  private static final String SOURCE = "Create a private POST /native-first-correction endpoint. "
      + "After entry run exactly this script: `" + SCRIPT + "`. Proceed without IDS.";

  enum Exclusion { MISSING_STORES, MISSING_EVIDENCE, OUTER_FALSE, PROJECTION_REBUILD, SPENT, FOREIGN, STALE, CHANGED_BASE,
    UPSTREAM_INPUT, DIAGNOSIS_DISPATCHED, LEGACY_DIAGNOSIS_DISPATCHED, MALFORMED_DIAGNOSIS, RESOURCE, CATALOG_WRITTEN }

  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts nativeArtifacts;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void firstFreshNativeRefusalUsesOneExistingCorrectionBeforeAnyDiagnosis() throws Exception {
    Fixture fixture = fixture(null);
    var originalSource = json.valueToTree(conversations.archive().read(fixture.chat));
    var sourceFacts = draft("receipt-script", fixture.chat).facts();
    var first = initialFailure(fixture, false);
    install(fixture, first);
    var failed = fixture.support.stageExecutor().execute(fixture.run, STAGE).await().atMost(Duration.ofSeconds(30));
    first.assertComplete();
    assertInstanceOf(StageDecision.Retry.class, failed.decision());
    verify(fixture.narrative, never()).diagnoseRecovery(anyString(), any());
    var originalRejection = drafts.rejectedCaptureRevision(fixture.chat).orElseThrow();
    var originalFeedback = feedback.lastRequirementRejection(fixture.chat).orElseThrow().feedback();
    assertEquals(CaptureFailureClass.CORRECTABLE, originalFeedback.failureClass());
    assertTrue(originalFeedback.outerAllowed());
    assertFalse(originalFeedback.noProgress());
    assertTrue(drafts.get(fixture.chat).isEmpty());
    var constraints = json.valueToTree(drafts.repairConstraints(fixture.chat));
    var before = fixture.runs.load(fixture.run).orElseThrow();
    assertEquals(1, repairIntents(before));
    var evidence = fixture.artifacts.latest(fixture.run, Kind.RECOVERY_EVIDENCE).orElseThrow();
    var saved = fixture.artifacts.payload(evidence, RecoveryEvidence.class);
    assertEquals(originalRejection.reference(), saved.captureFailure().rejectedCandidate());
    assertEquals(originalFeedback, saved.captureFailure().feedback());
    assertNotEquals(fixture.run, originalRejection.compilationId());
    var correction = new ScriptedStreamingChatModel(
        Turn.tool("first-native-correction", request -> {
          assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice());
          String packet = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).reduce("", (a, b) -> a + b);
          assertTrue(packet.contains(SOURCE));
          assertTrue(packet.contains("INVALID_ELEMENT_REFERENCE"));
          assertTrue(packet.contains(originalRejection.artifactId()));
          assertTrue(packet.contains(originalFeedback.evidenceReference().artifactId()));
          originalFeedback.issues().forEach(issue -> {
            assertTrue(packet.contains(issue.code()), issue.toString());
            assertTrue(packet.contains(issue.path()), issue.toString());
            assertTrue(packet.contains(issue.message()), issue.toString());
          });
          assertEquals(originalSource, json.valueToTree(conversations.archive().read(fixture.chat)));
          assertEquals(constraints, json.valueToTree(drafts.repairConstraints(fixture.chat)));
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", draft("receipt-script", fixture.chat)))),
        Turn.tool("finish-corrected-native-draft", request -> {
          assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
          var accepted = lastTool(request);
          assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
          assertEquals(json.valueToTree(sourceFacts), accepted.path("draft").path("facts"));
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("native-handoff", request -> {
          assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
          var accepted = lastTool(request);
          assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
          assertEquals("HANDOFF", accepted.path("nextAction").asText());
        }, "Requirements saved."));
    install(fixture, correction);
    var continued = fixture.support.stageExecutor().execute(fixture.run, STAGE).await().atMost(Duration.ofSeconds(30));
    correction.assertComplete();
    assertInstanceOf(StageDecision.Complete.class, continued.decision());
    verify(fixture.narrative, never()).diagnoseRecovery(anyString(), any());
    assertEquals(draft("receipt-script", fixture.chat), drafts.get(fixture.chat).orElseThrow().authoredDraft());
    assertConsumedSource(originalSource, fixture, drafts.latestRevision(fixture.chat).orElseThrow().reference());
    assertEquals(originalRejection, nativeArtifacts.get(originalRejection.compilationId(), originalRejection.reference()).orElseThrow());
    var reloaded = new ProductPipelineRunStore(fixture.blobs, json, Clock.systemUTC()).load(fixture.run).orElseThrow();
    assertEquals(1, repairIntents(reloaded));
    assertEquals(5, fixture.budget.state(fixture.run).requests());
    assertEquals(0, fixture.budget.state(fixture.run).grants());
    assertTrue(fixture.budget.state(fixture.run).active().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(Exclusion.class)
  void nativeCorrectionPermissionPreservesGuardedFallbackAndProjectionRebuild(Exclusion exclusion) throws Exception {
    Fixture fixture = fixture(exclusion);
    var sourceBefore = json.valueToTree(conversations.archive().read(fixture.chat));
    var script = initialFailure(fixture, exclusion == Exclusion.PROJECTION_REBUILD);
    install(fixture, script);
    if (exclusion == Exclusion.MALFORMED_DIAGNOSIS) {
      assertThrows(RuntimeException.class, () -> fixture.support.stageExecutor().execute(fixture.run, STAGE)
          .await().atMost(Duration.ofSeconds(30)));
    } else {
      var result = fixture.support.stageExecutor().execute(fixture.run, STAGE).await().atMost(Duration.ofSeconds(30));
      if (exclusion == Exclusion.PROJECTION_REBUILD) {
        var retry = assertInstanceOf(StageDecision.Retry.class, result.decision());
        assertEquals(STAGE, retry.stageId());
        assertFalse(retry.technicalRetry(), retry.toString());
      } else {
        assertFalse(result.decision() instanceof StageDecision.Retry, result.toString());
      }
    }
    script.assertComplete();
    assertEquals(exclusion == Exclusion.SPENT || exclusion == Exclusion.PROJECTION_REBUILD ? 1 : 0,
        repairIntents(fixture.runs.load(fixture.run).orElseThrow()));
    int diagnoses = switch (exclusion) {
      case DIAGNOSIS_DISPATCHED, LEGACY_DIAGNOSIS_DISPATCHED, MALFORMED_DIAGNOSIS, RESOURCE, CATALOG_WRITTEN, PROJECTION_REBUILD -> 0;
      default -> 1;
    };
    verify(fixture.narrative, times(diagnoses)).diagnoseRecovery(anyString(), any());
    if (exclusion == Exclusion.CHANGED_BASE) {
      assertEquals(draft("receipt-script", fixture.chat), drafts.get(fixture.chat).orElseThrow().authoredDraft());
      assertEquals(fixture.changedBase.get(), drafts.latestRevision(fixture.chat).orElseThrow().reference());
      assertConsumedSource(sourceBefore, fixture, fixture.changedBase.get());
    } else {
      assertTrue(drafts.get(fixture.chat).isEmpty());
    }
    if (exclusion != Exclusion.STALE && exclusion != Exclusion.CHANGED_BASE) {
      assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(fixture.chat)));
    }
    if (exclusion == Exclusion.PROJECTION_REBUILD) {
      var savedFeedback = feedback.lastRequirementRejection(fixture.chat).orElseThrow().feedback();
      assertFalse(savedFeedback.noProgress(), savedFeedback.toString());
      assertEquals(org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.Stage
          .PROJECTION_REBUILD, savedFeedback.ladderStage());
      assertTrue(drafts.rejectedCaptureRevision(fixture.chat).isEmpty(),
          "The rebuild must discard the rejected projection without accepting it.");
    }
    assertEquals(0, fixture.budget.state(fixture.run).grants());
    assertEquals(exclusion == Exclusion.PROJECTION_REBUILD ? 4 : 2, fixture.budget.state(fixture.run).requests());
    assertTrue(fixture.budget.state(fixture.run).active().isEmpty());
    if (exclusion == Exclusion.LEGACY_DIAGNOSIS_DISPATCHED) {
      var reloaded = new ProductPipelineRunStore(fixture.blobs, json, Clock.systemUTC()).load(fixture.run).orElseThrow();
      var oldInvocation = fixture.legacyInvocation.get();
      assertEquals(3, oldInvocation.size());
      assertFalse(oldInvocation.has("requirementBaseline"));
      var expectedDiagnosis = fixture.legacyDiagnosis.get();
      assertEquals(1, expectedDiagnosis.path("calls").asInt());
      assertEquals("TIMED_OUT", expectedDiagnosis.path("result").path("diagnosisStatus").asText());
      boolean retainedInvocation = false;
      boolean retainedDiagnosis = false;
      for (var attempt : reloaded.attempts()) {
        var evidence = ProductPipelineRunSupport.readHaltEvidence(attempt.failureEvidence());
        Object savedInvocation = evidence.get(ProductPipelineRunSupport.RECOVERY_INVOCATION_ATTR);
        if (savedInvocation instanceof String invocation && !invocation.isBlank()
            && json.readTree(invocation).equals(oldInvocation)) {
          retainedInvocation = true;
          assertFalse(json.readTree(invocation).has("requirementBaseline"));
        }
        Object savedDiagnosis = evidence.get(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR);
        if (savedDiagnosis instanceof String diagnosis && !diagnosis.isBlank()) {
          assertEquals(expectedDiagnosis, json.readTree(diagnosis),
              "An invocation metadata extension must not reset the saved diagnosis identity, calls, or timeout result.");
          retainedDiagnosis = true;
        }
      }
      assertTrue(retainedInvocation, "The exact old three-field invocation must survive a fresh durable reload.");
      assertTrue(retainedDiagnosis, "The existing calls=1 timeout must remain durable.");
      assertEquals(0, repairIntents(reloaded));
    }
  }

  private void assertConsumedSource(JsonNode original, Fixture fixture, Reference accepted) {
    var expected = original.deepCopy();
    assertEquals(1, expected.path("turns").size());
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("turns").get(0))
        .put("consumedDraftReference", accepted.artifactId());
    assertEquals(expected, json.valueToTree(conversations.archive().read(fixture.chat)));
  }

  private ScriptedStreamingChatModel initialFailure(Fixture fixture, boolean repeated) throws Exception {
    String arguments = json.writeValueAsString(Map.of("draft", draft("entry", fixture.chat)));
    var first = Turn.tool("invalid-local-reference", request -> assertNull(request.parameters().toolChoice()),
        "captureRequirementDraft", arguments);
    if (repeated) {
      return new ScriptedStreamingChatModel(first, Turn.tool("identical-native-proposal", request -> {
        var refused = lastTool(request);
        assertFalse(refused.path("accepted").asBoolean());
      }, "captureRequirementDraft", arguments),
          Turn.tool("request-projection-rebuild", request -> {
            var refused = lastTool(request);
            assertFalse(refused.path("accepted").asBoolean(), refused.toString());
            assertEquals("REPAIR_CAPTURE", refused.path("nextAction").asText());
          }, "captureRequirementDraft", arguments),
          Turn.text("retain-projection-rebuild", request -> {
            var refused = lastTool(request);
            assertFalse(refused.path("accepted").asBoolean(), refused.toString());
            assertEquals("PROJECTION_REBUILD", refused.path("nextAction").asText());
          }, "The requirements must be rebuilt from the author evidence."));
    }
    return new ScriptedStreamingChatModel(first, Turn.text("original-native-refusal", request -> {
      var refused = lastTool(request);
      assertFalse(refused.path("accepted").asBoolean(), refused.toString());
      assertTrue(refused.path("issues").toString().contains("INVALID_ELEMENT_REFERENCE"), refused.toString());
    }, "The generated local reference needs correction."));
  }

  private Fixture fixture(Exclusion exclusion) throws Exception {
    String chat = "native-before-diagnosis-" + UUID.randomUUID(), run = UUID.randomUUID().toString();
    var input = conversations.archive().beginInput(chat, SOURCE, null);
    conversations.archive().resolveInput(chat, input, SOURCE);
    var blobs = new InMemoryArtifactBlobStore();
    var runs = new ProductPipelineRunStore(blobs, json, Clock.systemUTC());
    var artifacts = new ProductPipelineArtifactStore(nativeArtifacts);
    var ledger = new RecoveryAttemptLedger();
    var narrative = mock(FailureNarrative.class);
    when(narrative.diagnoseRecovery(anyString(), any())).thenReturn(
        new FailureNarrative.RecoveryResult(FailureNarrative.RecoveryStatus.TIMED_OUT, null, "Existing diagnosis pause"));
    var budgetConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
    when(budgetConfig.llm().maxRequestsPerRun()).thenReturn(exclusion == Exclusion.RESOURCE ? 2 : 40);
    when(budgetConfig.llm().maxTokensPerRun()).thenReturn(1_000_000L);
    when(budgetConfig.llm().maxActiveTimePerRun()).thenReturn(Duration.ofMinutes(30));
    var budget = new LlmRunBudget(blobs, json, budgetConfig);
    var holder = new AtomicReference<Fixture>();
    StageCapability wrapped = new StageCapability() {
      public String capabilityId() { return STAGE; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        var attrs = new HashMap<>(context.attributes());
        attrs.put("userText", SOURCE);
        var selected = new StageExecutionContext(context.runId(), context.conversationId(), context.stageId(),
            context.executionKey(), context.attemptId(), context.profile(), context.runManifest(), context.inputRefs(), attrs);
        return discovery.execute(selected).onItem().transform(signal -> {
          if (!(signal instanceof CapabilitySignal.Completed completed)
              || completed.outcome().outcomeClass() != StageOutcomeClass.CONTRACT_FAILURE || exclusion == null) return signal;
          return exclude(holder.get(), selected, completed.outcome(), exclusion);
        });
      }
    };
    var inputType = new ArtifactTypeRef("user-input", 1);
    var profile = new ProductPipelineProfile(1, "native-first", "2", List.of(inputType),
        List.of(new ProfileStage(STAGE, STAGE, List.of(inputType), List.of(new ArtifactTypeRef("requirement-draft", 1)),
            null, null, new RetryPolicy(0, 1L))), new TerminalPolicy(STAGE, "PLAN_APPROVED"), List.of(STAGE));
    var manifest = new RunManifest(run, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile-hash", "baseline", "baseline-hash", List.of(), "closure",
        new KnowledgePackageRef("knowledge", "1", "1", "checksum", "CERTIFIED", "certificate"),
        "2026.1", List.of(), null);
    var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts,
        new StageCapabilityRegistry(List.of(wrapped)), Clock.systemUTC()).failureNarrative(narrative)
        .recoveryLedger(ledger).build().withLlmBudget(budget);
    if (exclusion != Exclusion.MISSING_STORES) support.withNativeRequirementCapture(drafts, feedback);
    ((ProductPipelineStageExecutor) support.stageExecutor()).withConversations(conversations);
    support.bootstrap(new StartOrResumeCommand(chat, run, profile, manifest), "native-first-flow");
    var provenance = new ArtifactProvenance(run, STAGE, "native-first", "2", "profile-hash", STAGE, "1", "closure");
    var sourceRef = artifacts.append(new CompilationArtifacts.AppendCommand(run, Kind.USER_INPUT, "1", "author", "1",
        new org.qubership.integration.platform.ai.productpipeline.artifact.UserInput("initial-source", STAGE, SOURCE,
            Clock.systemUTC().instant()), List.of(), null, provenance)).reference();
    Reference foreign = null;
    if (exclusion == Exclusion.FOREIGN) {
      String other = chat + "-foreign";
      var otherInput = conversations.archive().beginInput(other, SOURCE, null);
      conversations.archive().resolveInput(other, otherInput, SOURCE);
      drafts.beginTurn(other, SOURCE);
      try (var ignored = ToolSession.open(other)) { assertFalse(json.readTree(tools.captureRequirementDraft(draft("entry", other))).path("accepted").asBoolean()); }
      foreign = drafts.rejectedCaptureRevision(other).orElseThrow().reference();
    }
    var fixture = new Fixture(chat, run, blobs, runs, artifacts, support, ledger, narrative, budget, sourceRef, provenance,
        foreign, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    holder.set(fixture);
    return fixture;
  }

  private CapabilitySignal exclude(Fixture fixture, StageExecutionContext context, StageOutcome outcome, Exclusion exclusion) {
    try {
      var cause = outcome.recoveryCause();
      if (exclusion == Exclusion.OUTER_FALSE) {
        var current = feedback.lastRequirementRejection(fixture.chat).orElseThrow();
        feedback.recordClassifiedRequirementFailure(fixture.chat, current.feedback().kind(), CaptureFailureClass.CORRECTABLE,
            false, current.feedback().summary(), current.issues(), false);
        cause = new RecoveryCause(cause.causeCode(), cause.findings(), cause.requestedFact(), false,
            new CaptureFailureEvidence(feedback.lastRequirementRejection(fixture.chat).orElseThrow().feedback(),
                cause.captureFailure().acceptedBase(), cause.captureFailure().rejectedCandidate(), List.of()));
      } else if (exclusion == Exclusion.MISSING_EVIDENCE) {
        cause = new RecoveryCause(cause.causeCode(), cause.findings(), cause.requestedFact());
      } else if (exclusion == Exclusion.FOREIGN) {
        cause = new RecoveryCause(cause.causeCode(), cause.findings(), cause.requestedFact(), false,
            new CaptureFailureEvidence(cause.captureFailure().feedback(), cause.captureFailure().acceptedBase(), fixture.foreign, List.of()));
      } else if (exclusion == Exclusion.UPSTREAM_INPUT) {
        var nativeFinding = cause.findings().getFirst();
        cause = new RecoveryCause(cause.causeCode(), List.of(new PlanValidationFinding(nativeFinding.code(), nativeFinding.message(),
            true, nativeFinding.mappingDetails(), fixture.sourceReference.artifactId(), nativeFinding.fieldPointer())), "", false, cause.captureFailure());
      } else if (exclusion == Exclusion.STALE) {
        var later = conversations.archive().beginInput(fixture.chat, "Keep the original business behavior.", null);
        conversations.archive().resolveInput(fixture.chat, later, "Keep the original business behavior.");
      } else if (exclusion == Exclusion.CHANGED_BASE) {
        try (var ignored = ToolSession.open(fixture.chat)) {
          var accepted = json.readTree(tools.captureRequirementDraft(draft("receipt-script", fixture.chat)));
          assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
          fixture.changedBase.set(drafts.latestRevision(fixture.chat).orElseThrow().reference());
        }
      } else if (exclusion == Exclusion.CATALOG_WRITTEN) {
        fixture.artifacts.append(new CompilationArtifacts.AppendCommand(fixture.run, Kind.MATERIALIZATION_RESULT,
            "1", "catalog", "1", Map.of("chainId", "existing-chain"), List.of(), null, fixture.provenance));
      }
      if (exclusion == Exclusion.SPENT) {
        var doc = fixture.runs.load(fixture.run).orElseThrow();
        String identity = RecoveryAttemptLedger.inputArtifactIdentity(doc, STAGE);
        appendAttempt(fixture, fixture.ledger.recordRepair(fixture.ledger.key(STAGE, cause, identity, doc.transitions()), identity), null);
      } else if (exclusion == Exclusion.DIAGNOSIS_DISPATCHED || exclusion == Exclusion.LEGACY_DIAGNOSIS_DISPATCHED
          || exclusion == Exclusion.MALFORMED_DIAGNOSIS) {
        String state = "legacy-uninterpretable-diagnosis";
        String invocationState = null;
        if (exclusion == Exclusion.DIAGNOSIS_DISPATCHED || exclusion == Exclusion.LEGACY_DIAGNOSIS_DISPATCHED) {
          var doc = fixture.runs.load(fixture.run).orElseThrow();
          String input = RecoveryAttemptLedger.inputArtifactIdentity(doc, STAGE);
          JsonNode invocation = json.readTree(String.valueOf(fixture.support.runAttributes(fixture.run)
              .get(ProductPipelineRunSupport.RECOVERY_INVOCATION_ATTR)));
          if (exclusion == Exclusion.LEGACY_DIAGNOSIS_DISPATCHED) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) invocation).remove("requirementBaseline");
            fixture.legacyInvocation.set(invocation.deepCopy());
          } else {
            assertTrue(invocation.has("requirementBaseline"));
          }
          invocationState = invocation.toString();
          var identity = (com.fasterxml.jackson.databind.node.ObjectNode) invocation.deepCopy();
          identity.remove("requirementBaseline");
          String failure = CanonicalPayloadHash.sha256Hex(Map.of("invocation", identity, "stage", STAGE,
              "rejected", List.of(), "inputs", input, "defect", fixture.ledger.key(STAGE, cause, input, doc.transitions()).evidenceIdentity()));
          var result = json.createObjectNode().put("rejectedAuthorQuestion", false).put("diagnosisStatus", "TIMED_OUT");
          result.set("evidence", json.valueToTree(new RecoveryEvidence(1, failure, cause.causeCode().name(), STAGE, STAGE,
              null, null, List.of(), List.of(), null, List.of(), List.of(), context.inputRefs(), List.of(), false, cause.captureFailure())));
          var request = json.createObjectNode().put("identity", failure).put("calls", 1);
          request.set("result", result);
          state = request.toString();
          if (exclusion == Exclusion.LEGACY_DIAGNOSIS_DISPATCHED) fixture.legacyDiagnosis.set(request.deepCopy());
        }
        var saved = new HashMap<String, Object>();
        saved.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, outcome.message());
        saved.put(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR, state);
        if (invocationState != null) saved.put(ProductPipelineRunSupport.RECOVERY_INVOCATION_ATTR, invocationState);
        appendAttempt(fixture, "existing diagnosis dispatch", ProductPipelineRunSupport.haltEvidence(saved, null));
      }
      return new CapabilitySignal.Completed(StageOutcome.of(outcome.outcomeClass(), outcome.message(), cause));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private void appendAttempt(Fixture fixture, String reason, String evidence) {
    var doc = fixture.runs.load(fixture.run).orElseThrow();
    long revision = doc.run().runRevision();
    fixture.runs.commit(revision, LogicalCommit.statusChange(doc.run(), StageStatus.RUNNING, doc.run().stages(),
        new StageAttempt(UUID.randomUUID().toString(), STAGE, revision + 1, StageStatus.FAILED, Clock.systemUTC().instant(),
            Clock.systemUTC().instant(), List.of(), evidence),
        new RunTransition(revision, revision + 1, RunStatus.RUNNING, RunStatus.RUNNING, STAGE, Clock.systemUTC().instant(), reason)));
  }

  private void install(Fixture fixture, ScriptedStreamingChatModel script) {
    var boundary = new RateLimitStreamingChatModel(script, config, seconds -> {
      throw new AssertionError("Unexpected transport retry");
    }, new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(fixture.budget);
    doAnswer(call -> {
      boundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((a, b) -> b).orElseThrow().text());
    } catch (Exception error) { throw new AssertionError(error); }
  }

  private static long repairIntents(ProductPipelineRunDocument doc) {
    return doc.transitions().stream().filter(transition -> transition.reason() != null
        && transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)).count();
  }

  private DraftInput draft(String localReference, String chat) {
    var source = org.qubership.integration.platform.ai.plan.AuthorSourceEvidence.packet(conversations.archive().read(chat));
    var original = source.inputs().getFirst();
    int offset = original.text().indexOf(SCRIPT);
    assertTrue(offset >= 0);
    var body = new org.qubership.integration.platform.ai.plan.RequirementBodyBinding(
        org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY,
        localReference, "script", org.qubership.integration.platform.ai.plan.RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
        source.generation(), original.sourceTurnId(), offset, offset + SCRIPT.length(),
        org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding.RAW);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Private POST /native-first-correction endpoint.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var script = new FactInput("receipt", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Run exactly this script: exchange.message.body = 'receipt-confirmed'", null, null,
        new RequirementElementIntent(localReference, "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, null, null, null, List.of(new PlanProperty("script", SCRIPT)),
            List.of("receipt-confirmed"), body));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST /native-first-correction", null, null, null)), List.of()), List.of(endpoint, script),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/native-first-correction", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private record Fixture(String chat, String run, InMemoryArtifactBlobStore blobs, ProductPipelineRunStore runs,
      ProductPipelineArtifactStore artifacts, ProductPipelineRunSupport support, RecoveryAttemptLedger ledger,
      FailureNarrative narrative, LlmRunBudget budget, Reference sourceReference, ArtifactProvenance provenance,
      Reference foreign, AtomicReference<Reference> changedBase, AtomicReference<JsonNode> legacyInvocation,
      AtomicReference<JsonNode> legacyDiagnosis) {}
}
