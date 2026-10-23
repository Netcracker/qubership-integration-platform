package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.*;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.*;
import org.qubership.integration.platform.ai.productpipeline.knowledge.*;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.*;
import org.qubership.integration.platform.ai.qipknowledge.patch.*;
import org.qubership.integration.platform.ai.qipknowledge.validation.*;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.skill.executor.*;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.*;

/** The real capture, engine, adapter, dispatcher, and diagnosis boundary share one failed attempt. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class MandatoryCompilerFailureProviderTest {
  private static final String OWNER = "cip-security-generator";
  private static final String SOURCE = "Receive private POST /retained-progress and preserve the accepted context.";
  private static final String RAW = "{\"patch\":{\"patchId\":\"rejected\",\"rationale\":\"quoted\"} trailing}";
  @Inject ObjectMapper json;
  @Inject AppConfig config;
  @Inject CompilerSkillRuntime compiler;
  @Inject GraphPatchExecutionContextStore contexts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject CompilationArtifacts nativeArtifacts;
  @Inject CompilationSessions sessions;
  @Inject ConversationService conversations;
  @Inject FailureNarrativeAgent diagnosis;
  @InjectSpy E2eRecoveryTestControl recoveryControl;
  @TempDir Path evidence;
  @Inject CompilerValidationPipeline validators;
  @Inject CompilerPlanValidator planValidator;
  @Inject DeterministicElementSchemaService schemas;
  @InjectMock RateLimitStreamingChatModel streaming;
  @InjectMock RateLimitChatModel synchronous;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void failedMandatoryOwnerExportsUnapprovedProgressAndExactNativeRefusalToTheNextDiagnosis(
      boolean controlled, boolean large) throws Exception {
    String tail = "retained-raw-tail-43c9";
    String raw = large ? RAW + "x".repeat(14_000) + tail : RAW;
    String good = "{\"patch\":{\"patchId\":\"original-valid\",\"ownerCapabilityId\":\"" + OWNER
        + "\",\"nodePatches\":[],\"edgePatches\":[],\"propertyPatches\":[],\"chainPatches\":[],"
        + "\"usedKnowledgeRefs\":[],\"notApplicable\":true}}";
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    var h = new Harness();
    var controlDirectory = evidence.resolve("captures");
    Files.createDirectories(controlDirectory);
    var controlPlan = json.createObjectNode().put("schemaVersion", 1).put("conversationId", h.chat)
        .put("boundary", "checkpoint").put("mutation", controlled ? "mandatory-graph-json-exhaustion" : "none");
    if (controlled) controlPlan.set("graphCaptureFault", json.valueToTree(Map.of("capabilityId", OWNER,
        "toolName", "captureGraphPatch", "applications", 2, "injectedArguments", raw)));
    json.writeValue(controlDirectory.resolve("plan.json").toFile(), controlPlan);
    E2eRecoveryTestControl control = new E2eRecoveryTestControl(controlDirectory, json);
    var feedbackField = E2eRecoveryTestControl.class.getDeclaredField("feedbackStore");
    feedbackField.setAccessible(true);
    feedbackField.set(control, feedback);
    doAnswer(call -> control.graphArguments(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
        .when(recoveryControl).graphArguments(anyString(), anyString(), any());
    doAnswer(call -> { control.rejectedGraphArguments(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)); return null; })
        .when(recoveryControl).rejectedGraphArguments(anyString(), anyString(), any(), nullable(String.class));
    var recordedConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
    when(recordedConfig.llm()).thenReturn(config.llm());
    when(recordedConfig.e2e().recoveryControlDirectory()).thenReturn(Optional.of(controlDirectory.toString()));
    var model = new ScriptedStreamingChatModel(
        Turn.tool("bad-1", request -> assertTrue(request.messages().toString().contains("/retained-progress")), "captureGraphPatch", controlled ? good : raw),
        Turn.tool("bad-2", request -> {
          assertTrue(request.messages().toString().contains("INVALID_JSON"));
          assertParserDetails(request.messages().toString(), raw);
          var refusal = request.messages().stream()
              .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
              .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
              .filter(result -> "bad-1".equals(result.id())).findFirst().orElseThrow();
          assertEquals(feedback.lastPatchFailure(h.chat, OWNER).orElseThrow().summary(), refusal.text());
          String historic = request.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
              .flatMap(message -> message.toolExecutionRequests().stream()).filter(call -> "bad-1".equals(call.id()))
              .findFirst().orElseThrow().arguments();
          if (controlled) assertEquals(good, historic, "The original valid SDK call must stay unchanged.");
          else assertEquals(raw, jsonUnchecked(historic).path("rejectedToolCall").path("rawArguments").asText());
        }, "captureGraphPatch", controlled ? good : raw));
    var streamBoundary = new RateLimitStreamingChatModel(model, recordedConfig,
        seconds -> { throw new AssertionError("Unexpected provider retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(h.budget);
    doAnswer(call -> {
      streamBoundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var delivered = new AtomicReference<JsonNode>();
    var upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).filter(value -> value.startsWith("You diagnose create-chain recovery"))
          .findFirst().orElseThrow();
      int start = prompt.indexOf("\n\nContext: ") + "\n\nContext: ".length();
      int end = prompt.indexOf("\n\nReturn RecoveryDecision only.", start);
      assertTrue(delivered.compareAndSet(null, json.readTree(prompt.substring(start, end))));
      var packet = delivered.get().at("/evidence/captureFailure");
      var nativeFailure = feedback.lastPatchFailure(h.chat, OWNER).orElseThrow();
      assertEquals(json.valueToTree(nativeFailure), packet.path("feedback"));
      var saved = nativeArtifacts.get(sessions.active(h.chat), nativeFailure.evidenceReference()).orElseThrow();
      assertEquals(saved.payload(), packet.path("nativeObservation"));
      assertEquals("bad-2", packet.at("/nativeObservation/proposal/toolCallId").asText());
      assertEquals("captureGraphPatch", packet.at("/nativeObservation/proposal/toolName").asText());
      assertEquals(raw, packet.at("/nativeObservation/proposal/arguments").asText());
      if (large) {
        assertTrue(packet.at("/feedback/repairHistoryJson").asText().contains("argumentsOmitted"));
        assertFalse(packet.at("/feedback/repairHistoryJson").asText().contains(tail));
        assertTrue(packet.at("/nativeObservation/proposal/arguments").asText().endsWith(tail));
      }
      assertEquals(feedback.inputScope(h.chat, OWNER), packet.at("/nativeObservation/proposal/inputScope").asText());
      assertTrue(packet.at("/feedback/summary").asText().startsWith("INVALID_JSON /:"));
      assertParserDetails(packet.at("/feedback/summary").asText(), raw);
      assertEquals("INVALID_JSON /: Send one valid JSON argument object. Resubmit the complete captureGraphPatch argument object.",
          org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints
              .stripNativeParserDetails(packet.at("/feedback/summary").asText()));
      assertEquals("IDENTICAL_SPAM", packet.at("/feedback/failureClass").asText());
      assertFalse(packet.at("/feedback/outerAllowed").asBoolean());
      assertFalse(packet.at("/feedback/noProgress").asBoolean());
      assertEquals(json.valueToTree(h.partial.get().graph()), delivered.get().path("rejectedArtifact"));
      assertTrue(delivered.get().path("authorInputs").toString().contains(SOURCE));
      assertTrue(h.partial.get().patchLedger().orderedReferences().stream()
          .allMatch(ref -> h.artifacts.get(h.run, ref).isPresent()));
      JsonNode graphNode = java.util.stream.StreamSupport.stream(
          delivered.get().at("/evidence/rejectedArtifactRefs").spliterator(), false)
          .filter(ref -> Kind.CHAIN_PLAN_GRAPH.name().equals(ref.path("kind").asText())).findFirst().orElseThrow();
      var graphRef = json.treeToValue(graphNode, Reference.class);
      var decision = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, graphRef,
          List.of(delivered.get().at("/evidence/failureId").asText()), RecoveryAction.REGENERATE_ARTIFACT,
          List.of(), "", "Correct the rejected capture while preserving accepted progress.");
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(decision))).build();
    });
    var chatBoundary = new RateLimitChatModel(upstream, recordedConfig,
        seconds -> { throw new AssertionError("Unexpected provider retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(h.budget);
    doAnswer(call -> chatBoundary.chat(call.getArgument(0, ChatRequest.class))).when(synchronous).chat(any(ChatRequest.class));

    h.prepare();
    var approved = h.document().run().stages();
    var inputs = h.document().attempts().stream().map(StageAttempt::outputs).toList();
    var result = h.runtime.stageExecutor().execute(h.run, "design-execution").await().indefinitely();

    model.assertComplete();
    assertNotNull(delivered.get());
    assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
    assertEquals(RunStatus.WAITING_FOR_INPUT, h.document().run().status());
    assertEquals("design-execution", h.document().run().currentStageId());
    assertEquals(StageStatus.SUCCEEDED, h.document().run().stages().getFirst().status());
    assertEquals(approved.getFirst(), h.document().run().stages().getFirst());
    assertEquals(inputs.getFirst(), h.document().attempts().getFirst().outputs());
    var rejected = h.document().run().stages().stream().filter(stage -> "design-execution".equals(stage.stageId()))
        .findFirst().orElseThrow();
    assertEquals(StageStatus.WAITING_FOR_INPUT, rejected.status());
    assertNull(rejected.approvedArtifactId());
    assertEquals(Set.of(Kind.CHAIN_PLAN_GRAPH, Kind.ORDERED_GRAPH_PATCHES, Kind.PLAN_VALIDATION_RESULT),
        rejected.outputRefs().stream().map(Reference::kind).collect(java.util.stream.Collectors.toSet()));
    var graph = rejected.outputRefs().stream().filter(ref -> ref.kind() == Kind.CHAIN_PLAN_GRAPH).findFirst().orElseThrow();
    assertEquals(h.partial.get().graph(), h.artifacts.payload(h.artifacts.get(h.run, graph).orElseThrow(), ChainPlanGraph.class));
    var ledger = rejected.outputRefs().stream().filter(ref -> ref.kind() == Kind.ORDERED_GRAPH_PATCHES).findFirst().orElseThrow();
    assertEquals(h.partial.get().patchLedger().orderedReferences(), h.artifacts.payload(
        h.artifacts.get(h.run, ledger).orElseThrow(), OrderedGraphPatches.class).patchRefs());
    for (var kind : List.of(Kind.VALIDATED_EXECUTION_BUNDLE, Kind.MATERIALIZATION_REQUEST,
        Kind.DESIGN_EXECUTION_CHECKPOINT, Kind.EXECUTION_TRACE, Kind.GRAPH_ASSEMBLY_RESULT,
        Kind.COMPILER_VALIDATION_BUNDLE)) assertTrue(h.artifacts.history(h.run, kind).isEmpty(), kind.name());
    assertEquals(List.of("first-owner"), h.partial.get().executedSkillIds());
    assertEquals(1, h.partial.get().patchLedger().orderedReferences().size());
    assertEquals(0, h.budget.state(h.run).grants());
    assertEquals(3, h.budget.state(h.run).requests());
    assertTrue(h.document().transitions().stream().noneMatch(transition -> transition.reason() != null
        && (transition.reason().startsWith(RecoveryAttemptLedger.GRANT_REASON_PREFIX)
            || transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX))));
    var reloaded = new ProductPipelineRunStore(h.blobs, json, Clock.systemUTC()).load(h.run).orElseThrow();
    assertEquals(h.document(), reloaded);
    assertEquals(rejected.outputRefs(), reloaded.run().stages().getLast().outputRefs());
    assertEquals(model.requests().getFirst().parameters().toolSpecifications(), model.requests().getLast().parameters().toolSpecifications());
    if (controlled) {
      var events = new ArrayList<JsonNode>();
      try (var files = Files.list(controlDirectory.resolve("events"))) {
        for (var file : files.sorted().toList()) events.add(json.readTree(file.toFile()));
      }
      var applied = events.stream().filter(event -> "FAULT_APPLIED".equals(event.path("type").asText())).toList();
      assertEquals(2, applied.size());
      for (var event : applied) {
        assertEquals(good, event.path("originalWireArguments").asText());
        assertEquals(raw, event.path("injectedWireArguments").asText());
        assertFalse(event.path("rawSdkArgumentsChanged").asBoolean());
      }
      var third = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("future-valid").name("captureGraphPatch").arguments(good).build();
      assertSame(third, control.graphArguments(h.chat, OWNER, third), "The configured fault is exhausted after two calls.");
    }
    String export = System.getProperty("qip.test.controller-evidence");
    if (export != null) {
      Path output = Path.of(export).resolve((controlled ? "controlled" : "model") + (large ? "-large" : "-small"));
      Files.createDirectories(output);
      json.writeValue(output.resolve("evidence.json").toFile(), Map.of("runId", h.run, "conversationId", h.chat,
          "status", "NEGATIVE_PRESERVATION", "decision", result.decision().toString(), "requests", h.budget.state(h.run),
          "controlled", controlled, "large", large, "source", SOURCE, "recovered", false));
      json.writeValue(output.resolve("durable.json").toFile(), h.document());
      json.writeValue(output.resolve("actual-diagnosis-context.json").toFile(), delivered.get());
      json.writeValue(output.resolve("partial-result.json").toFile(), h.partial.get());
      var product = new LinkedHashMap<Kind, Object>();
      for (var kind : Kind.values()) if (!h.artifacts.history(h.run, kind).isEmpty()) product.put(kind, h.artifacts.history(h.run, kind));
      json.writeValue(output.resolve("artifacts.json").toFile(), product);
      json.writeValue(output.resolve("native-feedback.json").toFile(), nativeArtifacts.history(sessions.active(h.chat), Kind.CAPTURE_ATTEMPT_FEEDBACK));
      try (var files = Files.walk(controlDirectory)) {
        for (var file : files.filter(Files::isRegularFile).toList()) {
          Path target = output.resolve("raw-captures").resolve(controlDirectory.relativize(file));
          Files.createDirectories(target.getParent());
          Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  @org.junit.jupiter.api.Test
  void aPermittedNativeRefusalUsesOneExistingStageCorrectionAndRetainsItsAcceptedPatchLineage() throws Exception {
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    var h = new Harness();
    String tail = "permitted-raw-tail-847e";
    String raw = RAW + "x".repeat(14_000) + tail;
    String corrected = "{\"patch\":{\"patchId\":\"native-corrected\",\"ownerCapabilityId\":\"" + OWNER
        + "\",\"nodePatches\":[],\"edgePatches\":[],\"propertyPatches\":[],\"chainPatches\":[],"
        + "\"usedKnowledgeRefs\":[],\"notApplicable\":true}}";
    var turns = new ArrayList<Turn>();
    // The existing local runner and engine retry allowances are exhausted without inventing another refusal.
    for (int invocation = 0; invocation < 2; invocation++) {
      turns.add(Turn.tool("permitted-" + invocation, request -> {}, "captureGraphPatch", raw));
      for (int local = 0; local <= config.capture().maxRepairAttempts(); local++)
        turns.add(Turn.text("without-capture-" + invocation + "-" + local, request -> {}, "No capture submitted."));
    }
    turns.add(Turn.tool("genuine-native-correction", request -> {
      String context = request.messages().toString();
      assertTrue(context.contains("COMPILER_SKILL_FAILED"), context);
      assertTrue(context.contains(tail), "Full native raw data must reach the repair request.");
      assertTrue(context.contains("nativeObservation"));
      assertParserDetails(context, raw);
      assertTrue(context.contains(SOURCE));
    }, "captureGraphPatch", corrected));
    var model = new ScriptedStreamingChatModel(turns.toArray(Turn[]::new));
    var streamBoundary = new RateLimitStreamingChatModel(model, config,
        seconds -> { throw new AssertionError("Unexpected provider retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(h.budget);
    doAnswer(call -> { streamBoundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class)); return null; })
        .when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var delivered = new AtomicReference<JsonNode>();
    var denied = new AtomicReference<org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback>();
    var upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).filter(value -> value.startsWith("You diagnose create-chain recovery")).findFirst().orElseThrow();
      int start = prompt.indexOf("\n\nContext: ") + "\n\nContext: ".length();
      int end = prompt.indexOf("\n\nReturn RecoveryDecision only.", start);
      assertTrue(delivered.compareAndSet(null, json.readTree(prompt.substring(start, end))));
      var failure = feedback.lastPatchFailure(h.chat, OWNER).orElseThrow();
      denied.set(failure);
      assertEquals("CORRECTABLE", failure.failureClass().name());
      assertTrue(failure.outerAllowed());
      var packet = delivered.get().at("/evidence/captureFailure");
      assertEquals(json.valueToTree(failure), packet.path("feedback"));
      assertParserDetails(packet.at("/feedback/summary").asText(), raw);
      assertEquals("INVALID_JSON /: Send one valid JSON argument object. Resubmit the complete captureGraphPatch argument object.",
          org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints
              .stripNativeParserDetails(packet.at("/feedback/summary").asText()));
      assertEquals(raw, packet.at("/nativeObservation/proposal/arguments").asText());
      assertEquals("permitted-1", packet.at("/nativeObservation/proposal/toolCallId").asText());
      assertFalse(packet.at("/feedback/repairHistoryJson").asText().contains(tail));
      assertTrue(packet.at("/nativeObservation/proposal/arguments").asText().endsWith(tail));
      assertFalse(json.valueToTree(dev.langchain4j.internal.JsonSchemaElementUtils.toMap(
          request.parameters().responseFormat().jsonSchema().rootElement())).toString().contains("nativeObservation"));
      JsonNode selected = java.util.stream.StreamSupport.stream(delivered.get().at("/evidence/rejectedArtifactRefs").spliterator(), false)
          .filter(ref -> Kind.CHAIN_PLAN_GRAPH.name().equals(ref.path("kind").asText())).findFirst().orElseThrow();
      var decision = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, json.treeToValue(selected, Reference.class),
          List.of(delivered.get().at("/evidence/failureId").asText()), RecoveryAction.REGENERATE_ARTIFACT, List.of(), "",
          "Correct the rejected capture while preserving accepted progress.");
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(decision))).build();
    });
    var chatBoundary = new RateLimitChatModel(upstream, config, seconds -> { throw new AssertionError("Unexpected provider retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(h.budget);
    doAnswer(call -> chatBoundary.chat(call.getArgument(0, ChatRequest.class))).when(synchronous).chat(any(ChatRequest.class));
    h.prepare();
    var approved = h.document().run().stages().getFirst();
    var correction = h.runtime.stageExecutor().execute(h.run, "design-execution").await().indefinitely();
    assertInstanceOf(StageDecision.Retry.class, correction.decision());
    assertNotNull(delivered.get());
    var nativeReference = denied.get().evidenceReference();
    var nativeBefore = nativeArtifacts.get(sessions.active(h.chat), nativeReference).orElseThrow();
    var retained = h.partial.get().patchLedger().orderedReferences();
    assertEquals(1, retained.size());
    h.runtime.support().applyStageLifecycle(h.run, correction).collect().asList().await().indefinitely();
    var result = h.runtime.stageExecutor().execute(h.run, "design-execution").await().indefinitely();
    model.assertComplete();
    assertInstanceOf(StageDecision.Complete.class, result.decision());
    assertEquals(RunStatus.PLAN_APPROVED, h.document().run().status());
    assertEquals(approved, h.document().run().stages().getFirst());
    assertEquals(nativeBefore, nativeArtifacts.get(sessions.active(h.chat), nativeReference).orElseThrow());
    assertTrue(retained.stream().allMatch(ref -> h.artifacts.get(h.run, ref).isPresent()));
    var completed = h.completed.get();
    assertNotNull(completed);
    var owned = completed.patchLedger().orderedReferences().stream().filter(ref -> OWNER.equals(
        h.artifacts.payload(h.artifacts.get(h.run, ref).orElseThrow(), GraphPatchArtifact.class).ownerCapabilityId())).findFirst().orElseThrow();
    var admitted = h.artifacts.payload(h.artifacts.get(h.run, owned).orElseThrow(), GraphPatchArtifact.class);
    assertEquals("native-corrected", admitted.patchId());
    assertEquals(PatchApplicability.NOT_APPLICABLE, admitted.applicability());
    assertEquals(completed.patchLedger().orderedReferences(), completed.assemblyResult().orderedPatchReferences());
    assertTrue(completed.validationBundle().approvalEligible());
    assertTrue(h.artifacts.history(h.run, Kind.MATERIALIZATION_REQUEST).size() > 0);
    assertEquals(1, h.document().transitions().stream().filter(transition -> transition.reason() != null
        && transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)).count());
    assertEquals(0, h.document().transitions().stream().filter(transition -> transition.reason() != null
        && (transition.reason().startsWith(RecoveryAttemptLedger.CLEAN_REBUILD_REASON_PREFIX)
            || transition.reason().startsWith(RecoveryAttemptLedger.GRANT_REASON_PREFIX))).count());
    assertEquals(0, h.budget.state(h.run).grants());
  }

  private void assertParserDetails(String delivered, String raw) {
    try (var parser = json.getFactory().createParser(raw)) {
      try {
        json.readTree(parser);
        fail("The fixture must reach a real native syntax refusal.");
      } catch (com.fasterxml.jackson.core.JsonProcessingException nativeFailure) {
        assertTrue(delivered.contains("Native JSON parser details:"), delivered);
        assertTrue(delivered.contains("layer=outer"), delivered);
        assertTrue(delivered.contains("context=" + parser.getParsingContext().pathAsPointer()), delivered);
        assertTrue(delivered.contains("line=" + nativeFailure.getLocation().getLineNr()), delivered);
        assertTrue(delivered.contains("column=" + nativeFailure.getLocation().getColumnNr()), delivered);
        assertTrue(delivered.contains("charOffset=" + nativeFailure.getLocation().getCharOffset()), delivered);
        assertTrue(delivered.contains(nativeFailure.getOriginalMessage()), delivered);
      }
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
  }

  private JsonNode jsonUnchecked(String value) {
    try { return json.readTree(value); } catch (Exception failure) { throw new AssertionError(failure); }
  }

  private final class Harness {
    final String chat = "mandatory-owner-" + UUID.randomUUID();
    final String run = "run-" + chat;
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, json, Clock.systemUTC());
    final ProductPipelineArtifactStore artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, json, Clock.systemUTC()));
    final LlmRunBudget budget = new LlmRunBudget(blobs, json, config);
    final AtomicReference<CompilerDagExecutionResult> partial = new AtomicReference<>();
    final AtomicReference<CompilerDagExecutionResult> completed = new AtomicReference<>();
    final ChainPlanGraph graph;
    final ChainSemanticRevision semantic;
    final ResolvedCompilerDag dag;
    final RunManifest manifest;
    final DefaultCompilerDagExecutionEngine engine;
    final CreateChainTestOrchestrator runtime;
    final ProductPipelineProfile profile;
    CipDesignExecutorJavaAdapter.ExecutionInputs savedInputs;

    Harness() {
      var source = conversations.archive().beginInput(chat, SOURCE, null);
      conversations.archive().resolveInput(chat, source, SOURCE);
      graph = new ChainPlanGraph("1.0", new ChainSection("Retained", "Retained progress"), List.of(
          new ChainPlanNode("entry", "http-trigger", "Receive", null, 1, List.of(new PlanProperty("contextPath", "/initial"),
              new PlanProperty("httpMethodRestrict", "POST"), new PlanProperty("externalRoute", "false"), new PlanProperty("accessControlType", "NONE"))),
          new ChainPlanNode("respond", "script", "Respond", null, 2, List.of(new PlanProperty("script", "return 'ok'")))),
          List.of(new ChainPlanEdge("response", "entry", "respond", null)));
      var provenance = new SemanticProvenance(List.of());
      semantic = new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION, "revision", "Retained", CompilerContract.V1,
          List.of(new SemanticEntryPoint("entry", "entry", "respond", 0, provenance, null)),
          List.of(new SemanticNode.Trigger("entry", "http-trigger", provenance), new SemanticNode.Operation("respond", "script", provenance)),
          List.of(), List.of(new SemanticExecutionEdge("response", "entry", "respond", null, new SemanticRoute.Sequence(), null)),
          List.of(), List.of(), List.of(), List.of(), List.of());
      var nodes = new ArrayList<ResolvedCompilerNode>();
      nodes.add(node("first-owner", List.of(), 0, new GraphPatchOwnershipPolicy(false, false, Set.of(), Set.of(), Map.of("http-trigger", Set.of("contextPath")))));
      nodes.add(node(OWNER, List.of("first-owner"), 1, new GraphPatchOwnershipPolicy(false, false, Set.of(), Set.of(), Map.of("http-trigger", Set.of("externalRoute")))));
      nodes.add(new ResolvedCompilerNode("cip-chain-assembler", "Assembly", null, List.of("CHAIN_PLAN_GRAPH"), List.of("GRAPH_ASSEMBLY_RESULT"),
          List.of(OWNER), null, List.of(), List.of(), true, List.of(), 2, 0, true, CompilerNodeExecutionMode.JAVA_ADAPTER, "graph-assembly"));
      int order = 3;
      for (String id : CompilerValidationPipeline.validatorSkillIds()) nodes.add(new ResolvedCompilerNode(id,
          "Validation", null, List.of("GRAPH_ASSEMBLY_RESULT"), List.of("PRE_BUILD_VALIDATION"), List.of("cip-chain-assembler"),
          null, List.of(), List.of(), true, List.of(), order++, 0, true, CompilerNodeExecutionMode.JAVA_ADAPTER, id));
      dag = new ResolvedCompilerDag(nodes, List.of(), "mandatory-capture-dag");
      var pin = new CompilerRunPin("compiler", "1", "package-hash", 1, "1", "catalog-hash", dag,
          nodes.stream().map(ResolvedCompilerNode::skillId).toList(), Map.of(), Map.of(), List.of(), null, null, null, null, null, null);
      manifest = new RunManifest(run, null, List.of(), "product", "mandatory-capture", "1", "profile-hash", "baseline", "baseline-hash", List.of(), "closure",
          new KnowledgePackageRef("knowledge", "1", "1", "checksum", "CERTIFIED", "certificate"), "2026.1", List.of(), pin);
      var skills = mock(SkillExecutorRegistry.class);
      var prior = mock(SkillExecutor.class);
      when(prior.run(any(), any())).thenAnswer(call -> Uni.createFrom().item(SkillExecutionResult.completed(List.of(SkillArtifact.of(
          SkillArtifactType.GRAPH_PATCH, "first-owner", new SkillArtifactPayload.GraphPatchPayload(new GraphPatch("accepted-first", "first-owner",
              List.of(), List.of(), List.of(new PropertyPatch(GraphPatchOperation.UPDATE, "entry", new PlanProperty("contextPath", "/retained-progress"))),
              List.of(), List.of(), "Retain accepted progress")))), "accepted")));
      when(skills.require("first-owner")).thenReturn(prior);
      when(skills.require(OWNER)).thenReturn(new StreamingSkillExecutor() {
        public String skillId() { return OWNER; }
        public SkillExecutorKind kind() { return SkillExecutorKind.AGENT; }
        public Set<SkillArtifactType> requiredInputs() { return Set.of(SkillArtifactType.CHAIN_PLAN_GRAPH); }
        public Set<SkillArtifactType> outputTypes() { return Set.of(SkillArtifactType.GRAPH_PATCH); }
        public Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace) { return compiler.runStreaming(context, workspace, OWNER); }
        public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) { return compiler.run(context, workspace, OWNER); }
      });
      var pack = mock(CipKnowledgePackRepository.class);
      when(pack.activeVersion()).thenReturn(new CipKnowledgePackVersion("1", "1"));
      var digest = new CanonicalGraphDigest(json);
      var adapters = mock(CompilerNodeExecutionAdapterRegistry.class);
      var noop = mock(CompilerNodeExecutionAdapter.class);
      when(noop.execute(any(), any())).thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));
      when(adapters.require(anyString())).thenReturn(noop);
      engine = new DefaultCompilerDagExecutionEngine(new InMemorySkillWorkspaceStore(new ChainPlanStore()), skills,
          adapters, pack, contexts, new GraphPatchArtifactFactory(digest),
          new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier()), digest,
          new GraphAssemblyService(digest), validators, artifacts, new ChainPlanGraphValidator(schemas), new ClasspathCompilerContractRepository(), null);
      var prepare = capability("prepare", context -> Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED,
          List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief(), context.inputRefs())), "Saved inputs", null))));
      var execute = capability("execute", context -> {
        var brief = (RequirementBrief) context.attributes().get("requirementBrief");
        if (savedInputs == null) savedInputs = adapterInputs(brief);
        var runner = mock(ApprovedCompilerExecutionRunner.class);
        when(runner.execute(any(), any(), anyList(), any(), anyString(), any())).thenAnswer(call -> {
          try { var result = engine.execute(new CompilerDagExecutionRequest(run, chat, manifest, brief, semantic, dag,
              List.of("first-owner", OWNER), context.inputRefs(), CompilerExecutionSeed.forCreate(chat, brief, semantic, graph, List.of()),
              StageRepairEvidence.from(context)), context.attemptId(), call.getArgument(5)).await().indefinitely();
            completed.set(result); return result; }
          catch (CompilerDagExecutionFailure failure) { partial.set(failure.rejectedProgress()); throw failure; }
        });
        var binder = mock(ExecutorCatalogBindingAdapter.class);
        when(binder.resolve(anyString(), any(), anyList(), any())).thenReturn(List.of());
        var adapter = new CipDesignExecutorJavaAdapter(runner, binder, artifacts, planValidator);
        var signals = new ArrayList<CapabilitySignal>();
        var outcome = adapter.executeAfterApproval(savedInputs, context.attemptId(), (id, status) -> signals.add(new CapabilitySignal.SkillProgress(id, status)));
        signals.add(new CapabilitySignal.Completed(new StageOutcome(outcome.outcomeClass() == StageOutcomeClass.CANDIDATE
            ? StageOutcomeClass.SUCCEEDED : outcome.outcomeClass(), outcome.candidates(), outcome.message(), null, outcome.recoveryCause())));
        return Multi.createFrom().iterable(signals);
      });
      var briefType = new ArtifactTypeRef("requirement-brief", 1);
      profile = new ProductPipelineProfile(1, "mandatory-capture", "1", List.of(), List.of(
          new ProfileStage("preparation", prepare.capabilityId(), List.of(), List.of(briefType), null, null, new RetryPolicy(0, 0)),
          new ProfileStage("design-execution", execute.capabilityId(), List.of(briefType), List.of(new ArtifactTypeRef("chain-plan-graph", 1),
              new ArtifactTypeRef("ordered-graph-patches", 1), new ArtifactTypeRef("plan-validation-result", 1),
              new ArtifactTypeRef("graph-assembly-result", 1), new ArtifactTypeRef("compiler-validation-bundle", 1),
              new ArtifactTypeRef("executor-validation-bundle", 1), new ArtifactTypeRef("validated-execution-bundle", 1),
              new ArtifactTypeRef("materialization-request", 1), new ArtifactTypeRef("design-execution-checkpoint", 1),
              new ArtifactTypeRef("execution-trace", 1), new ArtifactTypeRef("api-operation-bindings", 1)), null, null, new RetryPolicy(0, 0))),
          new TerminalPolicy("design-execution", "PLAN_APPROVED"), List.of());
      var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts, new StageCapabilityRegistry(List.of(prepare, execute)), Clock.systemUTC())
          .compilerRunPinResolver(new CompilerRunPinResolver(new CompilerPipelineIndex(2,
              new CipKnowledgePackVersion("1", "1"), null, List.of(),
              new CompilerPackageIdentity("compiler", "1", pin.compilerPackageDigest()), Map.of(),
              nodes.stream().map(value -> new CompilerPipelineNode(value.skillId(), value.compilerPhase(), value.generatorId(),
                  value.consumes(), value.produces(), value.dependsOn(), value.captureTool(), value.applicabilitySignals(),
                  value.readinessSignals(), value.runtimeReady(), value.runtimeReadinessFindings(), null, null,
                  value.topologicalLevel(), value.stableTieBreaker(), value.mandatory(), value.executionMode(), value.adapterId())).toList(), List.of())))
          .failureNarrative(new FailureNarrative(diagnosis, 12, java.time.Duration.ofSeconds(20)))
          .recoveryLedger(new RecoveryAttemptLedger()).build().withLlmBudget(budget);
      runtime = new CreateChainTestOrchestrator(support, runs);
      ((ProductPipelineStageExecutor) runtime.stageExecutor()).withConversations(conversations);
      support.bootstrap(new StartOrResumeCommand(chat, run, profile, manifest), "mandatory-capture-flow");
    }

    void prepare() {
      var result = runtime.stageExecutor().execute(run, "preparation").await().indefinitely();
      assertInstanceOf(StageDecision.Continue.class, result.decision());
      runtime.support().applyStageLifecycle(run, result).collect().asList().await().indefinitely();
    }

    RequirementBrief brief() { return new RequirementBrief(SOURCE, List.of(), List.of(), List.of(), List.of(), SOURCE); }
    ProductPipelineRunDocument document() { return runs.load(run).orElseThrow(); }
    Reference append(Kind kind, Object payload, List<Reference> inputs) {
      String schema = kind == Kind.CHAIN_SEMANTIC_REVISION ? ChainSemanticRevision.CURRENT_SCHEMA_VERSION : kind == Kind.APPROVAL_RECORD ? "2" : "1";
      return artifacts.append(new CompilationArtifacts.AppendCommand(run, kind, schema, "fixture", "1", payload, inputs, null,
          new ArtifactProvenance(run, "design-execution", "mandatory-capture", "1", "profile-hash", "execute", "1", "closure"))).reference();
    }
    CipDesignExecutorJavaAdapter.ExecutionInputs adapterInputs(RequirementBrief brief) {
      var briefRef = artifacts.latest(run, Kind.REQUIREMENT_BRIEF).orElseThrow().reference();
      var revisionRef = append(Kind.CHAIN_SEMANTIC_REVISION, semantic, List.of(briefRef));
      var ids = new IdsDocument("1", IdsDocument.Mode.PROVIDED, briefRef.artifactId(), briefRef.contentHash(), "flow", "fixture", "# Saved inputs\n");
      var idsRef = append(Kind.IDS_DOCUMENT, ids, List.of(briefRef));
      var report = new DesignPlanReport("1", "# Saved plan\n");
      var reportRef = append(Kind.DESIGN_PLAN_REPORT, report, List.of(revisionRef));
      var steps = new ArrayList<DesignExecutionPlan.Step>();
      int ordinal = 1;
      for (var node : dag.nodes()) steps.add(new DesignExecutionPlan.Step("step-" + ordinal, ordinal++, node.skillId(), DesignExecutionPlan.OwnerKind.SKILL,
          List.of(node.skillId()), List.of(), List.of(), List.of(), List.of(), List.of(), node.produces()));
      var plan = new DesignExecutionPlan("1", semantic.revisionId(), "fixture", revisionRef.artifactId(), "input-hash", "2026.1", ApprovalPolicy.CATALOG_FIRST_V1,
          steps, reportRef.artifactId(), reportRef.contentHash(), Map.of(), Map.of(), manifest.compilerRunPin().pipelineIndexDigest(), ApprovalPolicy.CATALOG_FIRST_V1_HASH);
      var planRef = append(Kind.DESIGN_EXECUTION_PLAN, plan, List.of(reportRef, revisionRef));
      var implementation = new ImplementationPlan("Execute the saved plan.");
      var implementationRef = append(Kind.IMPLEMENTATION_PLAN, implementation, List.of(planRef));
      var manifestRef = append(Kind.RUN_MANIFEST, manifest, List.of());
      var approval = new ApprovalRecordV2(implementationRef, implementationRef.contentHash(), List.of(idsRef, revisionRef, reportRef, planRef, implementationRef),
          "fixture-author", "Approved saved plan", Instant.EPOCH, ApprovalPolicy.CATALOG_FIRST_V1, ApprovalPolicy.CATALOG_FIRST_V1_HASH, null, null, null, null, null, null);
      var approvalRef = append(Kind.APPROVAL_RECORD, approval, approval.approvedCandidates());
      return new CipDesignExecutorJavaAdapter.ExecutionInputs(run, chat, approvalRef, approval, report, reportRef, plan, planRef, semantic, revisionRef, ids, idsRef,
          implementation, implementationRef, manifest, manifestRef, List.of(), null, null, null);
    }
  }

  private static ResolvedCompilerNode node(String owner, List<String> depends, int order, GraphPatchOwnershipPolicy policy) {
    return new ResolvedCompilerNode(owner, "Implementation", "GEN-" + order, List.of("CHAIN_PLAN_GRAPH"), List.of("CHAIN_PLAN_GRAPH", "GRAPH_PATCH"),
        depends, "captureGraphPatch", List.of(), List.of(), true, List.of(), order, 0, true, CompilerNodeExecutionMode.LLM_SKILL, null, policy);
  }
  private static StageCapability capability(String id, java.util.function.Function<StageExecutionContext, Multi<CapabilitySignal>> execute) {
    return new StageCapability() {
      public String capabilityId() { return id; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) { return execute.apply(context); }
    };
  }
}
