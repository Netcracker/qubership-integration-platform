package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitAwareChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitAwareStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitErrorClassifier;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitWaitPolicy;
import org.qubership.integration.platform.ai.llm.ratelimit.TransientErrorClassifier;
import org.qubership.integration.platform.ai.llm.ratelimit.TransientWaitPolicy;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSidecarApi;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DefaultKnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.SidecarKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.orchestration.SkillSubgraph;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Uses the native compiler, tool argument mapper, capture policy, schema service, and graph consumer. The
 * design-input cases run the stage on its blocking agent with the same scripted model.
 */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class ScriptedRecoveryTest {
  private static final String CAPABILITY = "cip-trigger-generator";
  private static final String TOOL = "captureHttpTriggers";
  private static final String VALID = """
      {"capture":{"endpoints":[{"roleId":"http-entry","semanticNodeId":"http-trigger-1","externalRoute":false}]}}
      """;

  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RateLimitChatModel blockingProvider;
  @Inject DesignInputCapability designInput;
  @Inject dev.langchain4j.store.memory.chat.ChatMemoryStore chatMemory;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;
  @Inject CompilerSkillRuntime runtime;
  @Inject CaptureSession captures;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject AppConfig config;

  private String conversation;
  private InMemorySkillWorkspace workspace;
  private ChainPlanGraph originalGraph;
  private ScriptedStreamingChatModel script;
  private final List<Integer> waits = new ArrayList<>();
  private final List<RateLimitAwareChatModel.BackoffEvent> backoffs = new ArrayList<>();

  @BeforeEach
  void setUp() {
    conversation = "scripted-recovery-" + UUID.randomUUID();
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    when(knowledge.documentationManifest()).thenReturn(
        new KnowledgeSidecarApi.DocumentationManifestDto(1, "sha256:" + "d".repeat(64), 1, 1));
    when(knowledge.searchDocumentation(any(), any(), any(), anyInt())).thenReturn(
        new KnowledgeSidecarApi.DocumentationSearchDto("sha256:" + "d".repeat(64), List.of()));
    var approved = HttpTriggerCaptureAdapterTest.binding();
    HttpTriggerCaptureSession.unbind("http-capture-test");
    workspace = new InMemorySkillWorkspace(conversation);
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_SEMANTIC_REVISION, "design-input",
        new SkillArtifactPayload.ChainSemanticRevisionPayload(approved.revision())));
    workspace.put(SkillArtifact.of(SkillArtifactType.REQUIREMENT_BRIEF, "requirement-analysis",
        new SkillArtifactPayload.RequirementBriefPayload(approved.brief())));
    workspace.put(SkillArtifact.of(SkillArtifactType.ELEMENT_SKELETON, "pattern-selector",
        new SkillArtifactPayload.ElementSkeletonPayload(approved.skeleton())));
    originalGraph = new ChainPlanGraph("1.0", new ChainSection("Tasks", "Create tasks"), List.of(
        new ChainPlanNode("http-trigger-1", "http-trigger", "Receive", null, 1, List.of()),
        new ChainPlanNode("response-1", "script", "Respond", null, 2,
            List.of(new PlanProperty("script", "return 'created'")))),
        List.of(new ChainPlanEdge("receive-response", "http-trigger-1", "response-1", null)));
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "design-execution",
        new SkillArtifactPayload.ChainPlanGraphPayload(originalGraph)));
  }

  @AfterEach
  void clear() {
    HttpTriggerCaptureSession.unbind(conversation);
    for (CaptureSlot slot : List.of(CaptureSlot.CONFIGURED_TRIGGER_SET, CaptureSlot.APPROVED_REQUIREMENT_BRIEF)) {
      captures.clear(CaptureKey.conversation(slot, conversation));
    }
    feedback.clearPlan(conversation);
    feedback.endScope(conversation, CAPABILITY);
    ToolSession.clear();
    org.jboss.logmanager.MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  static Stream<Arguments> invalidCaptures() {
    return Stream.of(
        Arguments.of("malformed-json", "{\"capture\":", "JSON"),
        Arguments.of("wrong-type", VALID.replace(":false", ":\"false\""), "externalRoute"),
        Arguments.of("missing-field", VALID.replace(",\"externalRoute\":false", ""), "externalRoute"),
        Arguments.of("unknown-field", VALID.replace("\"endpoints\":", "\"properties\":[],\"endpoints\":"), "properties"),
        Arguments.of("duplicate-key", VALID.replace("\"endpoints\":", "\"endpoints\":[],\"endpoints\":"), "DUPLICATE_JSON_KEY"),
        Arguments.of("unknown-reference", VALID.replace("http-trigger-1", "absent-node"), "is not an approved semanticNodeId"),
        Arguments.of("incomplete-set", "{\"capture\":{\"endpoints\":[]}}", "Send one endpoint for each of"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCaptures")
  void repairsNativeRejectionsAndTheCompilerConsumesTheResult(String name, String invalid, String diagnostic) {
    install(Turn.tool(name, this::expectInitial, TOOL, invalid),
        Turn.tool("corrected", expectRejection(diagnostic), TOOL, VALID));

    assertConsumed(run());
    script.assertComplete();
    assertTrue(waits.isEmpty());
  }

  @Test
  void aSecondDefectCanBeRepairedWithoutLosingTheFirstCorrection() {
    install(Turn.tool("wrong-type", this::expectInitial, TOOL, VALID.replace(":false", ":\"false\"")),
        Turn.tool("bad-reference", expectRejection("externalRoute"), TOOL, VALID.replace("http-trigger-1", "absent")),
        Turn.tool("corrected", expectRejection("is not an approved semanticNodeId"), TOOL, VALID));

    assertConsumed(run());
    script.assertComplete();
  }

  @Test
  void differentSchemaFieldsWithTheSameExplanationReachTheConsumer() {
    install(Turn.tool("first-type", this::expectInitial, TOOL, VALID.replace(":false", ":0")),
        Turn.tool("second-type", expectRejection("externalRoute"), TOOL,
            VALID.replace("\"http-trigger-1\"", "false")),
        Turn.tool("corrected", expectRejection("semanticNodeId"), TOOL, VALID));
    assertConsumed(run());
    script.assertComplete();
  }

  @Test
  void cosmeticChangesDoNotCreateNewCreditForTheSameTypedDefect() {
    List<Turn> turns = new ArrayList<>();
    turns.add(Turn.tool("bad-reference", this::expectInitial, TOOL, VALID.replace("http-trigger-1", "absent-0")));
    for (int index = 1; index < 2; index++) {
      turns.add(Turn.tool("same-defect-" + index, expectRejection("is not an approved semanticNodeId"), TOOL,
          VALID.replace("http-trigger-1", "absent-" + index)));
    }
    install(turns.toArray(Turn[]::new));

    assertEquals(SkillRunStatus.FAILED, run().status());
    assertFalse(captures.isPresent(captureKey()));
    assertTrue(feedback.lastPlanFailure(conversation).orElseThrow().summary().contains("is not an approved semanticNodeId"));
    assertEquals(originalGraph, ((SkillArtifactPayload.ChainPlanGraphPayload)
        workspace.get(SkillArtifactType.CHAIN_PLAN_GRAPH).orElseThrow().payload()).graph());
    assertEquals(2, script.requests().size());
    assertEquals(org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.IDENTICAL_SPAM,
        feedback.lastPlanFailure(conversation).orElseThrow().failureClass());
    script.assertComplete();
  }

  /**
   * The tool result carries the rejection back to the model, and the model's next call in the same turn is
   * accepted. An outer repair would add a second user message to the request that delivers the rejection.
   */
  @Test
  void aWrongRoleIsCorrectedInsideTheTurnThatSentIt() {
    install(Turn.tool("wrong-role", this::expectInitial, TOOL, VALID.replace("http-entry", "absent-role")),
        Turn.tool("corrected", expectRejection("absent-role"), TOOL, VALID));

    assertConsumed(run());
    script.assertComplete();
    assertInstanceOf(ToolExecutionResultMessage.class, script.requests().getLast().messages().getLast());
    assertEquals(1, userMessages(script.requests().getLast()));
  }

  /**
   * Design-input runs a blocking agent, so its tool loop is exercised through the stage rather than the compiler
   * runtime. The rejected graph and its correction are one provider turn, and the stage harvests the accepted
   * revision.
   */
  @Test
  void aRejectedDesignGraphIsCorrectedInsideTheTurnThatSentIt() throws Exception {
    install(Turn.tool("ghost-edge", request -> assertEquals(1, userMessages(request)),
            ChainSemanticCaptureTool.TOOL_NAME, designArguments("""
                [{"sourceNodeId":"http-in","targetNodeId":"op-shared"},
                 {"sourceNodeId":"op-shared","targetNodeId":"ghost-call"}]""")),
        Turn.tool("corrected", request -> {
          assertInstanceOf(ToolExecutionResultMessage.class, request.messages().getLast());
          assertTrue(((ToolExecutionResultMessage) request.messages().getLast()).text().contains("ghost-call"),
              () -> request.messages().getLast().toString());
          assertEquals(1, userMessages(request));
        }, ChainSemanticCaptureTool.TOOL_NAME, designArguments(null)));

    StageOutcome outcome = runDesignInput();

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass(), outcome.message());
    assertTrue(outcome.candidates().stream().anyMatch(candidate -> candidate.kind() == Kind.CHAIN_SEMANTIC_REVISION),
        () -> "candidates: " + outcome.candidates());
    script.assertComplete();
    // The blocking agent stores a turn only when it completes, so the turn the accepted capture
    // ended leaves no unanswered tool call in the memory the chat conversation shares.
    assertEquals(List.of(), chatMemory.getMessages(conversation));
  }

  /**
   * A turn spends its six tool calls on distinct rejected graphs. The stage reports the last rejection as a
   * contract failure, the outcome a single rejected capture has, rather than the framework's limit error.
   */
  @Test
  void aDesignTurnThatRunsOutOfToolCallsReportsItsLastRejection() throws Exception {
    // Six calls run; the seventh response asks for a call the limit refuses to execute.
    List<Turn> turns = new ArrayList<>();
    for (int index = 1; index <= 7; index++) {
      turns.add(Turn.tool("ghost-" + index, request -> { }, ChainSemanticCaptureTool.TOOL_NAME,
          designArguments("""
              [{"sourceNodeId":"http-in","targetNodeId":"op-shared"},
               {"sourceNodeId":"op-shared","targetNodeId":"ghost-%d"}]""".formatted(index))));
    }
    install(turns.toArray(Turn[]::new));

    StageOutcome outcome = runDesignInput();

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    assertTrue(outcome.message().contains("ghost-6"), outcome.message());
    script.assertComplete();
  }

  @Test
  void aSuccessStatementWithoutCaptureDoesNotCompleteTheCompilerStep() {
    install(Turn.text("claim", this::expectInitial, "Everything is repaired and valid."));

    assertEquals(SkillRunStatus.FAILED, run().status());
    assertFalse(captures.isPresent(captureKey()));
    script.assertComplete();
  }

  @Test
  void rateLimitAndTransientRetriesStillNeedToolAdmissionAndConsumerContinuation() {
    install(Turn.failure("rate-limit", this::expectInitial,
            new RateLimitException("rate_limit_exceeded Please try again in 812ms.")),
        Turn.failure("unavailable", this::expectInitial,
            new InternalServerException("Service Unavailable", new HttpException(503, "Service Unavailable"))),
        Turn.tool("bad-reference", this::expectInitial, TOOL, VALID.replace("http-trigger-1", "absent")),
        Turn.tool("corrected", expectRejection("is not an approved semanticNodeId"), TOOL, VALID));

    assertConsumed(run());
    assertEquals(List.of(1, 2), waits);
    assertEquals(1, backoffs.size(), "Backoff events describe 429 waits; transient waits are recorded by the sleeper");
    assertEquals(script.requests().get(0), script.requests().get(1));
    assertEquals(script.requests().get(1), script.requests().get(2));
    script.assertComplete();
  }

  private void install(Turn... turns) {
    script = new ScriptedStreamingChatModel(turns);
    var rate = config.llm().rateLimit();
    var transientRetry = config.llm().transientRetry();
    var wrapped = new RateLimitAwareStreamingChatModel(script,
        new RateLimitErrorClassifier(), new TransientErrorClassifier(), new RateLimitWaitPolicy(),
        TransientWaitPolicy.fromCsv(transientRetry.backoffSeconds()), waits::add,
        rate.enabled(), rate.maxAttempts(), transientRetry.enabled(), transientRetry.maxAttempts(), backoffs::add);
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      wrapped.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  private SkillExecutionResult run() {
    var context = new SkillRunContext(conversation, CAPABILITY, CipKnowledgePackFixturePaths.PACK_DIR,
        SkillSubgraph.BUILD_CHAIN, 1, false, "", "", "run-" + conversation);
    runtime.runStreaming(context, workspace, CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(20));
    return runtime.run(context, workspace, CAPABILITY).await().atMost(Duration.ofSeconds(20));
  }

  private void expectInitial(ChatRequest request) {
    assertTrue(userText(request).contains("http-trigger-1"));
    assertTrue(userText(request).contains("/tasks"));
    assertTrue(userText(request).contains("POST"));
    assertFalse(captures.isPresent(captureKey()));
  }

  private Consumer<ChatRequest> expectRejection(String diagnostic) {
    return request -> {
      expectInitial(request);
      var rejection = feedback.lastPlanFailure(conversation).orElseThrow(() -> new AssertionError(
          "Native rejection is missing; last model message: " + request.messages().getLast()));
      assertNotNull(rejection.evidenceReference(), "Native rejection must be persisted");
      var last = request.messages().getLast();
      String delivered = last instanceof ToolExecutionResultMessage tool ? tool.text()
          : last instanceof UserMessage user ? user.singleText() : "";
      assertTrue(delivered.contains(diagnostic), delivered);
      if (last instanceof ToolExecutionResultMessage && !rejection.issues().isEmpty()) {
        var nativeResult = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
          var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
          var parsed = mapper.readTree(delivered);
          return parsed.isTextual() ? mapper.readTree(parsed.asText()) : parsed;
        });
        var issue = rejection.issues().getFirst();
        assertTrue(java.util.stream.StreamSupport.stream(nativeResult.path("issues").spliterator(), false)
            .anyMatch(value -> issue.code().equals(value.path("code").asText())
                && issue.path().equals(value.path("path").asText())
                && issue.message().equals(value.path("message").asText())), delivered);
      } else {
        assertTrue(delivered.contains(rejection.summary()), delivered);
      }
    };
  }

  /** Runs design-input once on the blocking agent, with the scripted model and a permissive catalog. */
  private StageOutcome runDesignInput() {
    when(blockingProvider.chat(any(ChatRequest.class)))
        .thenAnswer(call -> script.respond(call.getArgument(0)));
    // The capture preflight loads a runtime descriptor for every element type from the catalog.
    var descriptors = org.mockito.Mockito.mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptors);
    io.quarkus.test.junit.QuarkusMock.installMockForType(descriptors, CatalogElementDescriptorLoader.class);
    return designInput.execute(designContext()).collect().asList()
        .await().atMost(Duration.ofSeconds(20)).stream()
        .filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome();
  }

  private static long userMessages(ChatRequest request) {
    return request.messages().stream().filter(UserMessage.class::isInstance).count();
  }

  private static String designArguments(String edges) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var capture = mapper.valueToTree(ChainSemanticCaptureFixtures.linearCapture());
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) capture;
    if (edges != null) {
      tree.set("edges", mapper.readTree(edges));
    }
    for (var edge : tree.path("edges")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) edge).put("routeKind", "SEQUENCE");
    }
    return mapper.writeValueAsString(java.util.Map.of("capture", tree));
  }

  private StageExecutionContext designContext() {
    return new StageExecutionContext("run-" + conversation, conversation, DesignInputCapability.CAPABILITY_ID,
        "exec-1", "attempt-1",
        new ProductPipelineProfile(1, "fixture", "2", List.of(), List.of(), null, List.of()),
        new RunManifest("run-" + conversation, null, List.of(), "product", "fixture", "2", "sha", "baseline",
            "bsha", List.of(), "csha", null, "24.4", List.of(), null),
        List.of(),
        java.util.Map.of("userText", "Create the orders chain.",
            "requirementBrief", ChainSemanticCaptureFixtures.approvedBrief()));
  }

  private static String userText(ChatRequest request) {
    return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .map(UserMessage::singleText).reduce("", (left, right) -> left + "\n" + right);
  }

  private CaptureKey captureKey() {
    return CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversation);
  }

  private void assertConsumed(SkillExecutionResult result) {
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    var accepted = captures.get(captureKey(), ConfiguredTriggerSet.class).orElseThrow();
    assertEquals("http-trigger-1", accepted.triggers().getFirst().semanticNodeId());
    var graph = result.outputs().stream().filter(output -> output.type() == SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(output -> ((SkillArtifactPayload.ChainPlanGraphPayload) output.payload()).graph()).findFirst().orElseThrow();
    assertEquals(originalGraph.chain(), graph.chain());
    assertEquals(originalGraph.edges(), graph.edges());
    assertEquals(originalGraph.nodes().get(1), graph.nodes().get(1));
    assertTrue(graph.nodes().getFirst().properties().containsAll(List.of(
        new PlanProperty("contextPath", "/tasks"), new PlanProperty("httpMethodRestrict", "POST"),
        new PlanProperty("externalRoute", "false"))));
    assertTrue(feedback.lastPlanFailure(conversation).isEmpty());
  }
}
