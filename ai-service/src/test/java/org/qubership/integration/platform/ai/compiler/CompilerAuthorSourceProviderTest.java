package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.AuthorSourceEvidence;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DefaultKnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.SidecarKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.orchestration.SkillSubgraph;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/** Checks complete source delivery at the registered provider, then uses native tools and graph consumption. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class CompilerAuthorSourceProviderTest {
  private static final String SCRIPT_CAPABILITY = "cip-script-generator";
  private static final String SECURITY_CAPABILITY = "cip-security-generator";
  private static final String INTERPRETATION = "Filter incoming item records and return the selected IDs.";
  private static final String ALGORITHM = """
      Accept a JSON body containing an items array. The input is already parsed into an object.
      The array is optional; a missing or null items property represents an empty collection.
      Inspect every record in the original order. Records that are not objects do not participate.
      Select a record only when active is the boolean true. Neither a string nor a missing active property qualifies.
      A selected record also needs a numeric count greater than zero. Do not convert strings into numbers.
      Preserve each selected id exactly as supplied, including leading zeroes and non-ASCII characters.
      Return a new object with exactly two properties: count and ids. Count is the number of selected records.
      The ids array follows the same order as the original records; do not sort or deduplicate it.
      An empty or missing input collection produces count=0 and ids=[]. Do not invent a default ID.
      Read the input body without changing its records, active values, counts, or IDs.
      Keep filtering and response construction in one local Groovy script after the HTTP trigger.
      The response must contain no rejected rows, temporary working arrays, or original request body.
      Strings containing quotes, braces such as {original}, or separators must remain exact ID strings.
      Do not put the algorithm into an inline HTTP script hook or call an external filtering service.
      Do not add a loop element, extra chain, exception wrapper, catalog operation, or logging of the body.
      The script must retain the active=true condition even when every example record happens to be active.
      """;
  private static final String CLARIFICATION = "A null count also excludes the record. Return duplicate IDs unchanged.";
  private static final String SCRIPT = """
      def payload = exchange.message.body
      def rows = payload instanceof Map && payload.items instanceof Collection ? payload.items : []
      def selected = rows.findAll { row -> row instanceof Map && row.active == true && row.count instanceof Number && row.count > 0 }
      exchange.message.body = [count: selected.size(), ids: selected.collect { it.id }]
      """;

  @Inject ObjectMapper json;
  @Inject CompilerSkillRuntime runtime;
  @Inject ConversationService conversations;
  @Inject ChainPlanStore plans;
  @Inject GraphPatchExecutionContextStore contexts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject AppConfig config;
  @InjectSpy CompilerSkillAddonRepository addons;
  @InjectSpy CaptureRouter captureRouter;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;
  private String chat;

  @BeforeEach
  void knowledgeFixture() {
    chat = "compiler-author-source-" + UUID.randomUUID();
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
  }

  @AfterEach
  void clear() {
    for (String capability : List.of(SCRIPT_CAPABILITY, SECURITY_CAPABILITY)) {
      contexts.clear(chat, capability);
      feedback.clearPatch(chat, capability);
      feedback.endScope(chat, capability);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scriptRepairKeepsConsumedAuthorAlgorithmThroughTheNativeRefusalAndContinues(boolean standalone) throws Exception {
    doReturn(standalone).when(addons).hasStandaloneSkills();
    var source = source();
    var graph = graph(false);
    var workspace = workspace(graph);
    var ownership = GraphPatchOwnershipPolicy.denyAll().withAdditionalProperties(Map.of("script", Set.of("script")));
    var bound = bind(SCRIPT_CAPABILITY, graph, ownership);
    var limits = limits();
    var model = new ScriptedStreamingChatModel(
        Turn.tool("blank-body", request -> {
          assertSource(request, source);
          assertSourceOwnedPredicates(request);
        }, "repairScriptBodies", capture("")),
        Turn.tool("correct-body", request -> {
          assertSource(request, source);
          assertSourceOwnedPredicates(request);
          assertTrue(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
              .map(ToolExecutionResultMessage.class::cast).anyMatch(result -> result.text().contains("script body is blank")));
          assertEquals(graph, plans.get(chat).orElseThrow(), "A rejected body cannot mutate the graph.");
          assertEquals(bound.ownership(), contexts.get(chat, SCRIPT_CAPABILITY).orElseThrow().ownership());
        }, "repairScriptBodies", capture(SCRIPT)));
    install(model);
    var context = context(SCRIPT_CAPABILITY);
    runtime.runStreaming(context, workspace, SCRIPT_CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(30));
    var result = runtime.run(context, workspace, SCRIPT_CAPABILITY).await().atMost(Duration.ofSeconds(30));
    model.assertComplete();
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    var consumed = result.outputs().stream().filter(artifact -> artifact.type() == SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(artifact -> ((SkillArtifactPayload.ChainPlanGraphPayload) artifact.payload()).graph()).findFirst().orElseThrow();
    var script = consumed.nodes().stream().filter(node -> "filter".equals(node.nodeId()))
        .flatMap(node -> node.properties().stream()).filter(property -> "script".equals(property.key()))
        .map(PlanProperty::value).findFirst().orElseThrow();
    assertEquals(SCRIPT.trim(), script);
    assertEquals(graph.nodes().getFirst(), consumed.nodes().getFirst());
    assertEquals(graph.edges(), consumed.edges());
    assertEquals(source, conversations.archive().read(chat));
    assertEquals(INTERPRETATION, ((SkillArtifactPayload.RawUserRequestPayload) workspace.get(SkillArtifactType.RAW_USER_REQUEST)
        .orElseThrow().payload()).effectiveText(), "Source evidence must not overwrite the approved interpretation.");
    assertEquals(limits, limits());
    assertEquals(2, model.requests().size());
    assertScriptBehavior(script);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void normalScriptCompilerPublishesSourceOwnedPredicates(boolean standalone) throws Exception {
    doReturn(standalone).when(addons).hasStandaloneSkills();
    doReturn(new CaptureRoute(SCRIPT_CAPABILITY,
        org.qubership.integration.platform.ai.compiler.addon.CaptureTool.CAPTURE_GRAPH_PATCH))
        .when(captureRouter).routeFor(SCRIPT_CAPABILITY);
    var source = source();
    var graph = graph(false);
    var workspace = workspace(graph);
    bind(SCRIPT_CAPABILITY, graph,
        GraphPatchOwnershipPolicy.denyAll().withAdditionalProperties(Map.of("script", Set.of("script"))));
    String arguments = json.writeValueAsString(Map.of("patch", Map.of("patchId", "source-script",
        "ownerCapabilityId", SCRIPT_CAPABILITY, "propertyPatches", List.of(Map.of("operation", "ADD",
            "targetNodeId", "filter", "key", "script", "value", SCRIPT)))));
    var model = new ScriptedStreamingChatModel(Turn.tool("native-script-patch", request -> {
      assertSource(request, source);
      assertSourceOwnedPredicates(request);
    }, "captureGraphPatch", arguments));
    install(model);
    var context = context(SCRIPT_CAPABILITY);
    runtime.runStreaming(context, workspace, SCRIPT_CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(30));
    var result = runtime.run(context, workspace, SCRIPT_CAPABILITY).await().atMost(Duration.ofSeconds(30));
    model.assertComplete();
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    assertEquals(1, model.requests().size());
    assertEquals(source, conversations.archive().read(chat));
  }

  private static void assertSourceOwnedPredicates(ChatRequest request) {
    String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .map(UserMessage::singleText).reduce("", (first, next) -> first + "\n" + next);
    assertTrue(text.contains("Use only approved success and failure predicates"), text);
    assertTrue(text.contains("Missing, null, empty, blank, false, and numeric zero are distinct"), text);
    assertTrue(text.contains("Whitespace-only strings are truthy"), text);
    assertTrue(text.contains("Preserve the approved grouping and order of conditions and arithmetic"), text);
    assertFalse(text.contains("def failed = (status != null && status >= 400)"), text);
    assertFalse(text.contains("A successful response with errors=[] is success"), text);
  }

  @Test
  void normalCompilerRequestReceivesTheSameSeparateCurrentAuthorEvidence() throws Exception {
    var source = source();
    var graph = graph(true);
    var workspace = workspace(graph);
    bind(SECURITY_CAPABILITY, graph,
        GraphPatchOwnershipPolicy.denyAll().withAdditionalProperties(Map.of("http-trigger", Set.of("accessControlType"))));
    var model = new ScriptedStreamingChatModel(Turn.tool("native-security-patch", request -> assertSource(request, source),
        "captureGraphPatch", """
        {"patch":{"patchId":"security","ownerCapabilityId":"cip-security-generator","propertyPatches":[
          {"operation":"UPDATE","targetNodeId":"entry","key":"accessControlType","value":{},"scalarValue":"NONE"}]}}
        """));
    install(model);
    var context = context(SECURITY_CAPABILITY);
    runtime.runStreaming(context, workspace, SECURITY_CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(30));
    var result = runtime.run(context, workspace, SECURITY_CAPABILITY).await().atMost(Duration.ofSeconds(30));
    model.assertComplete();
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    assertEquals(source, conversations.archive().read(chat));
  }

  private ConversationArchive.Document source() {
    var archive = conversations.archive();
    var old = archive.beginInput(chat, "OBSOLETE_GENERATION_ALGORITHM", null);
    archive.resolveInput(chat, old, "OBSOLETE_GENERATION_ALGORITHM");
    conversations.clearMessages(chat);
    var original = archive.beginInput(chat, ALGORITHM, null);
    archive.resolveInput(chat, original, ALGORITHM);
    archive.consume(chat, original.generation(), List.of(original.turnId()), "approved-draft");
    conversations.addMessage(chat, ConversationMessage.assistant("ASSISTANT_ONLY_UNAPPROVED_REWRITE"));
    var clarification = archive.beginInput(chat, CLARIFICATION, null);
    archive.resolveInput(chat, clarification, CLARIFICATION);
    archive.consume(chat, clarification.generation(), List.of(clarification.turnId()), "approved-draft");
    archive.beginInput(chat, "PENDING_UNRESOLVED_AUTHOR_INPUT", null);
    return archive.read(chat);
  }

  private void assertSource(ChatRequest request, ConversationArchive.Document source) {
    String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .map(UserMessage::singleText).reduce("", (first, next) -> first + "\n" + next);
    assertTrue(text.contains("Saved author evidence, in chronological order:"), text);
    int interpretationStart = text.indexOf("Approved requirement interpretation:\n");
    int briefStart = text.indexOf("Requirement brief:", interpretationStart);
    assertTrue(interpretationStart >= 0 && briefStart > interpretationStart, text);
    assertTrue(text.substring(interpretationStart, briefStart).contains(INTERPRETATION), text);
    assertFalse(text.substring(interpretationStart, briefStart).contains("Select a record only when active"),
        "The source packet must remain separate from the approved interpretation.");
    assertTrue(text.contains("evidence, not permission to change approved facts"), text);
    assertFalse(request.parameters().toolSpecifications().stream().anyMatch(tool ->
        Set.of("captureRequirementDraft", "updateRequirementDraft").contains(tool.name())),
        "Author evidence does not expose requirement amendment tools to the compiler.");
    assertFalse(text.contains("OBSOLETE_GENERATION_ALGORITHM"));
    assertFalse(text.contains("ASSISTANT_ONLY_UNAPPROVED_REWRITE"));
    assertFalse(text.contains("PENDING_UNRESOLVED_AUTHOR_INPUT"));
    try {
      JsonNode packet = json.readTree(json.createParser(text.substring(text.indexOf("{\"generation\""))));
      assertEquals(json.valueToTree(AuthorSourceEvidence.packet(source)), packet);
      assertEquals(ALGORITHM, packet.path("inputs").get(0).path("text").asText());
      assertEquals(CLARIFICATION, packet.path("inputs").get(1).path("text").asText());
      assertTrue(ALGORITHM.length() > 1_000);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private InMemorySkillWorkspace workspace(ChainPlanGraph graph) {
    var workspace = new InMemorySkillWorkspace(chat);
    workspace.put(SkillArtifact.of(SkillArtifactType.RAW_USER_REQUEST, "accepted-interpretation",
        new SkillArtifactPayload.RawUserRequestPayload(INTERPRETATION, List.of())));
    workspace.put(SkillArtifact.of(SkillArtifactType.REQUIREMENT_BRIEF, "requirement-analysis",
        new SkillArtifactPayload.RequirementBriefPayload(brief())));
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "design-execution",
        new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    plans.put(chat, graph);
    return workspace;
  }

  private GraphPatchExecutionContext bind(String capability, ChainPlanGraph graph, GraphPatchOwnershipPolicy ownership) {
    var bound = new GraphPatchExecutionContext("run-" + chat, capability, "", null, "", "2026.1", brief(), List.of(), graph,
        ownership, "attempt");
    contexts.set(chat, capability, bound);
    return bound;
  }

  private static RequirementBrief brief() {
    return new RequirementBrief(INTERPRETATION, List.of(), List.of(), List.of(), List.of(), "Approved brief summary.");
  }

  private static ChainPlanGraph graph(boolean filled) {
    return new ChainPlanGraph("1.0", new ChainSection("Filter", "Filter item records"), List.of(
        new ChainPlanNode("entry", "http-trigger", "Receive", null, 1, List.of(new PlanProperty("contextPath", "/filter"),
            new PlanProperty("httpMethodRestrict", "POST"), new PlanProperty("externalRoute", "false"),
            new PlanProperty("accessControlType", "NONE"))),
        new ChainPlanNode("filter", "script", "Filter", null, 2, filled ? List.of(new PlanProperty("script", SCRIPT)) : List.of())),
        List.of(new ChainPlanEdge("receive-filter", "entry", "filter", null)));
  }

  private String capture(String script) throws Exception {
    return json.writeValueAsString(Map.of("capture", new ScriptBodyRepairCapture("body", List.of(new ScriptBodyEntry("filter", script)),
        "Apply the saved filtering requirements without changing the graph or approval.")));
  }

  private SkillRunContext context(String capability) {
    return new SkillRunContext(chat, capability, CipKnowledgePackFixturePaths.PACK_DIR, SkillSubgraph.BUILD_CHAIN, 1, false,
        "", "", "run-" + chat);
  }

  private void install(ScriptedStreamingChatModel model) {
    doAnswer(call -> {
      model.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  private static void assertScriptBehavior(String script) {
    for (var vector : List.of(Map.<String, Object>of(), Map.<String, Object>of("items", List.of(
        Map.of("active", true, "count", 2, "id", "0007"), Map.of("active", false, "count", 3, "id", "excluded"),
        Map.of("active", true, "count", 1, "id", "0007"), Map.of("active", true, "count", "2", "id", "excluded"))))) {
      var message = new HashMap<String, Object>();
      message.put("body", vector);
      new groovy.lang.GroovyShell(new groovy.lang.Binding(Map.of("exchange", Map.of("message", message)))).evaluate(script);
      assertEquals(vector.isEmpty() ? Map.of("count", 0, "ids", List.of()) : Map.of("count", 2, "ids", List.of("0007", "0007")),
          message.get("body"));
    }
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
