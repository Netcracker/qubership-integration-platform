package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitAwareChatModel;
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
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
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

/** Uses the native compiler, tool argument mapper, capture policy, schema service, and graph consumer. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class ScriptedRecoveryTest {
  private static final String CAPABILITY = "cip-trigger-generator";
  private static final String TOOL = "captureHttpTriggers";
  private static final String VALID = """
      {"capture":{"endpoints":[{"roleId":"http-entry","semanticNodeId":"http-trigger-1","externalRoute":false}]}}
      """;

  @InjectMock RateLimitStreamingChatModel provider;
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
        Arguments.of("unknown-reference", VALID.replace("http-trigger-1", "absent-node"), "approved targets"),
        Arguments.of("incomplete-set", "{\"capture\":{\"endpoints\":[]}}", "every approved HTTP trigger"));
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
        Turn.tool("corrected", expectRejection("approved targets"), TOOL, VALID));

    assertConsumed(run());
    script.assertComplete();
  }

  @Test
  void cosmeticChangesDoNotResetTheConfiguredRepairAllowance() {
    List<Turn> turns = new ArrayList<>();
    turns.add(Turn.tool("bad-reference", this::expectInitial, TOOL, VALID.replace("http-trigger-1", "absent-0")));
    for (int index = 1; index < 2 + config.capture().maxRepairAttempts(); index++) {
      turns.add(Turn.tool("same-defect-" + index, expectRejection("approved targets"), TOOL,
          VALID.replace("http-trigger-1", "absent-" + index)));
    }
    install(turns.toArray(Turn[]::new));

    assertEquals(SkillRunStatus.FAILED, run().status());
    assertFalse(captures.isPresent(captureKey()));
    assertTrue(feedback.lastPlanFailure(conversation).orElseThrow().summary().contains("approved targets"));
    assertEquals(originalGraph, ((SkillArtifactPayload.ChainPlanGraphPayload)
        workspace.get(SkillArtifactType.CHAIN_PLAN_GRAPH).orElseThrow().payload()).graph());
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
        Turn.tool("corrected", expectRejection("approved targets"), TOOL, VALID));

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
      assertTrue(delivered.contains(rejection.summary()), delivered);
    };
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
