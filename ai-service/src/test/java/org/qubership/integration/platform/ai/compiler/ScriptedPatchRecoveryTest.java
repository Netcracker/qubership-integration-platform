package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DefaultKnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.SidecarKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.patch.*;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.orchestration.*;
import org.qubership.integration.platform.ai.skill.workspace.*;

/** Exercises rejection delivery through the native tool, schema validator, memory, and provider. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class ScriptedPatchRecoveryTest {
  private static final String CAPABILITY = "cip-security-generator";
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;
  @Inject CompilerSkillRuntime runtime;
  @Inject GraphPatchExecutionContextStore contexts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject org.qubership.integration.platform.ai.compiler.capture.CaptureSession captures;
  @Inject org.qubership.integration.platform.ai.plan.ChainPlanStore plans;
  @Inject ObjectMapper json;
  @Inject dev.langchain4j.store.memory.chat.ChatMemoryStore memory;
  @io.quarkus.test.junit.mockito.InjectSpy org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer sanitizer;
  private String conversation;
  private ChainPlanGraph graph;
  private InMemorySkillWorkspace workspace;
  private ScriptedStreamingChatModel script;

  @BeforeEach
  void setUp() {
    conversation = "patch-recovery-" + UUID.randomUUID();
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    graph = new ChainPlanGraph("1.0", new ChainSection("Receive", "Receive a request"), List.of(
        new ChainPlanNode("entry", "http-trigger", "Receive", null, 1, List.of(
            new PlanProperty("contextPath", "/receive"), new PlanProperty("httpMethodRestrict", "POST"),
            new PlanProperty("externalRoute", "false"), new PlanProperty("accessControlType", "NONE"))),
        new ChainPlanNode("respond", "script", "Respond", null, 2,
            List.of(new PlanProperty("script", "return 'ok'")))),
        List.of(new ChainPlanEdge("receive-response", "entry", "respond", null)));
    workspace = new InMemorySkillWorkspace(conversation);
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "input",
        new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    plans.put(conversation, graph);
    contexts.set(conversation, CAPABILITY, new GraphPatchExecutionContext("run", CAPABILITY, "", null,
        "", "2026.1", null, List.of(), graph,
        GraphPatchOwnershipPolicy.denyAll().withAdditionalProperties(Map.of("http-trigger",
            Set.of("roles", "abacParameters", "accessControlType", "externalRoute", "chunked", "privateRoute", "responseFilter"))), "attempt"));
  }

  @AfterEach
  void clear() {
    contexts.clear(conversation, CAPABILITY);
    feedback.clearPatch(conversation, CAPABILITY);
    feedback.endScope(conversation, CAPABILITY);
  }

  @Test
  void everyNestedSchemaFailureReachesTheNextModelRequest() throws Exception {
    String invalid = patch("{\"operation\":\"UPDATE\",\"targetNodeId\":\"entry\",\"key\":\"abacParameters\","
        + "\"value\":{\"resourceType\":false,\"operation\":false,\"resourceDataType\":\"invalid-type\"}}");
    install(Turn.tool("invalid", request -> {}, "captureGraphPatch", invalid),
        Turn.tool("corrected", request -> {
          String delivered = delivered(request);
          assertTrue(delivered.contains("/properties/abacParameters/resourceDataType"), delivered);
          assertTrue(delivered.contains("/properties/abacParameters/resourceType"), delivered);
          assertTrue(delivered.contains("/properties/abacParameters/operation"), delivered);
          assertTrue(delivered.contains("invalid-type"), delivered);
          assertTrue(delivered.contains("String") && delivered.contains("Map"), delivered);
        }, "captureGraphPatch", patch("{\"operation\":\"UPDATE\",\"targetNodeId\":\"entry\",\"key\":\"abacParameters\","
            + "\"value\":{\"resourceType\":\"CHAIN\",\"operation\":\"ALL\",\"resourceDataType\":\"String\",\"resourceString\":\"orders\"}}")));
    runAndAssertConsumed();
  }

  @Test
  void partialCorrectionsKeepEveryRemainingIssueAndConditionalRequirement() throws Exception {
    addProbe();
    var valid = settings();
    var invalid = valid.deepCopy();
    for (String key : List.of("first", "second", "third", "fourth")) {
      invalid.put(key, false);
    }
    invalid.put("mode", "unknown");
    invalid.remove("mapping");
    var partial = invalid.deepCopy();
    partial.put("first", valid.path("first").asText());
    partial.put("second", valid.path("second").asText());
    install(Turn.tool("six-errors", request -> {}, "captureGraphPatch", probePatch(invalid)),
        Turn.tool("partial", request -> {
          var issues = feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().issues();
          assertEquals(6, issues.size());
          assertTrue(delivered(request).contains("permitted-option-159-"), delivered(request));
          assertTrue(delivered(request).contains("/properties/settings/mapping"), delivered(request));
          assertTrue(delivered(request).contains("conditions") && delivered(request).contains("then"));
        }, "captureGraphPatch", probePatch(partial)),
        Turn.tool("complete", request -> {
          var issues = feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().issues();
          assertEquals(4, issues.size());
          assertFalse(issues.stream().anyMatch(issue -> issue.path().endsWith("/first") || issue.path().endsWith("/second")));
          assertTrue(issues.stream().anyMatch(issue -> issue.path().endsWith("/mapping")));
          assertTrue(delivered(request).contains("repair-history"));
        }, "captureGraphPatch", probePatch(valid)));
    runAndAssertConsumed();
  }

  @Test
  void repeatedLongEnumsDoNotEvictTheNativeRefusal() throws Exception {
    addProbe();
    var ids = List.of("probe", "probe-two", "probe-three", "probe-four");
    var nodes = new java.util.ArrayList<>(graph.nodes());
    for (String id : ids.subList(1, ids.size())) {
      nodes.add(new ChainPlanNode(id, "repair-schema-probe", id, null, 3,
          List.of(new PlanProperty("settings", settings().toString()))));
    }
    var edges = new java.util.ArrayList<ChainPlanEdge>();
    String previous = "entry";
    for (String id : ids) {
      edges.add(new ChainPlanEdge(previous + "-" + id, previous, id, null));
      previous = id;
    }
    edges.add(new ChainPlanEdge("probe-response", previous, "respond", null));
    graph = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, edges);
    plans.put(conversation, graph);
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "input",
        new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    var bound = contexts.get(conversation, CAPABILITY).orElseThrow();
    contexts.set(conversation, CAPABILITY, new GraphPatchExecutionContext("run", CAPABILITY, "", null,
        "", "2026.1", null, List.of(), graph, bound.ownership(), "attempt"));
    var bad = settings();
    bad.put("mode", "unsupported");
    install(Turn.tool("four-enums", request -> {}, "captureGraphPatch", patches(ids, bad)),
        Turn.tool("four-fixed", request -> {
          assertEquals(4, feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().issues().size());
          for (int i = 0; i < 160; i++) {
            assertTrue(delivered(request).contains("permitted-option-" + i + "-"),
                "Native schema enum was lost before model correction: " + i);
          }
          for (String id : ids) {
            assertTrue(delivered(request).contains(id));
          }
        }, "captureGraphPatch", patches(ids, settings())));
    runAndAssertConsumed();
  }

  private String patches(List<String> ids, com.fasterxml.jackson.databind.JsonNode value) {
    var root = json.createObjectNode();
    var patch = root.putObject("patch").put("patchId", "patch").put("ownerCapabilityId", CAPABILITY);
    var operations = patch.putArray("propertyPatches");
    for (String id : ids) {
      operations.addObject().put("operation", "UPDATE").put("targetNodeId", id)
          .put("key", "settings").put("scalarValue", value.toString());
    }
    patch.putArray("usedKnowledgeRefs");
    return root.toString();
  }

  @Test
  void sequentialCorrectionsContinueWithinTheExistingOuterRepairAllowance() throws Exception {
    addProbe();
    var valid = settings();
    var remaining = valid.deepCopy();
    var keys = List.of("first", "second", "third", "fourth", "mode", "mapping");
    for (String key : keys.subList(0, 4)) {
      remaining.put(key, false);
    }
    remaining.put("mode", "unsupported");
    remaining.remove("mapping");
    var turns = new java.util.ArrayList<Turn>();
    turns.add(Turn.tool("initial-six", request -> {}, "captureGraphPatch", probePatch(remaining)));
    for (int i = 0; i < keys.size(); i++) {
      int expectedCount = keys.size() - i;
      remaining.set(keys.get(i), valid.get(keys.get(i)));
      turns.add(Turn.tool("fix-" + keys.get(i), request -> assertEquals(expectedCount,
          feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().issues().size()),
          "captureGraphPatch", probePatch(remaining.deepCopy())));
    }
    install(turns.toArray(Turn[]::new));
    runAndAssertConsumed();
  }

  @Test
  void changingOnlyAnInvalidValueDoesNotCreateRepairCredit() throws Exception {
    addProbe();
    var first = settings();
    first.put("mode", "unsupported-first");
    var second = settings();
    second.put("mode", "unsupported-second");
    install(Turn.tool("same-constraint-first", request -> {}, "captureGraphPatch", probePatch(first)),
        Turn.tool("same-constraint-second", request -> assertTrue(feedback.lastPatchFailure(conversation, CAPABILITY)
            .orElseThrow().outerAllowed()), "captureGraphPatch", probePatch(second)));
    assertEquals(SkillRunStatus.FAILED, run().status());
    assertFalse(feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().outerAllowed());
    assertEquals(2, script.requests().size());
    script.assertComplete();
  }

  @Test
  void missingToolResponseIsRebuiltWithTheCompleteLongEnum() throws Exception {
    addProbe();
    var invalid = settings();
    invalid.put("mode", "unsupported");
    var omit = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.stubbing.Answer<Object> loseResponse = call -> {
      if (omit.getAndSet(false)) {
        String id = call.getArgument(0);
        memory.updateMessages(id, memory.getMessages(id).stream()
            .filter(message -> !(message instanceof ToolExecutionResultMessage tool && tool.id().equals("long-enum"))).toList());
      }
      return call.callRealMethod();
    };
    doAnswer(loseResponse).when(sanitizer).repairDanglingToolCalls(anyString());
    doAnswer(loseResponse).when(sanitizer).repairDanglingToolCalls(anyString(), anyString());
    install(Turn.tool("long-enum", request -> {}, "captureGraphPatch", probePatch(invalid)),
        Turn.text("finish-without-capture", request -> {
          String nativeResponse = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
              .map(ToolExecutionResultMessage.class::cast).filter(tool -> tool.id().equals("long-enum"))
              .findFirst().orElseThrow().text();
          assertTrue(nativeResponse.contains("permitted-option-159-"));
          assertTrue(nativeResponse.length() > 800);
          var nativeFeedback = feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow();
          String saved = nativeFeedback.issues().isEmpty() ? nativeFeedback.summary() : json.valueToTree(nativeFeedback.issues()).toString();
          assertTrue(saved.contains("permitted-option-159-"));
          assertTrue(saved.length() > 6000);
          omit.set(true);
        }, "I will correct the rejected value."),
        Turn.tool("fixed-enum", request -> {
          var restored = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
              .map(ToolExecutionResultMessage.class::cast).filter(tool -> tool.id().equals("long-enum"))
              .findFirst().map(ToolExecutionResultMessage::text).orElse("");
          var lastUser = request.messages().stream().filter(UserMessage.class::isInstance)
              .map(UserMessage.class::cast).toList().getLast().singleText();
          for (int i = 0; i < 160; i++) {
            assertTrue(lastUser.contains("permitted-option-" + i + "-"),
                "The next repair user message lost enum member " + i + "; restored result: " + restored);
          }
          if (!restored.isEmpty()) {
            assertTrue(restored.contains("permitted-option-159-"), restored);
            assertTrue(restored.contains("unsupported"), restored);
            assertTrue(restored.contains("settings"), restored);
          }
        }, "captureGraphPatch", probePatch(settings())));
    runAndAssertConsumed();
  }

  @Test
  void nestedMapKeysUseExactJsonPointers() throws Exception {
    var bad = json.readTree("{\"resourceType\":\"CHAIN\",\"operation\":\"ALL\",\"resourceDataType\":\"Map\",\"resourceMap\":{\"a/b~c\":false}}");
    String invalid = propertyPatch("entry", "abacParameters", bad);
    var good = bad.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) good.path("resourceMap")).put("a/b~c", "orders");
    install(Turn.tool("nested-key", request -> {}, "captureGraphPatch", invalid),
        Turn.tool("fixed-key", request -> assertTrue(delivered(request)
            .contains("/properties/abacParameters/resourceMap/a~1b~0c"), delivered(request)),
            "captureGraphPatch", propertyPatch("entry", "abacParameters", good)));
    runAndAssertConsumed();
  }

  @Test
  void rootConditionalRequirednessReachesTheModel() throws Exception {
    install(Turn.tool("rbac-without-roles", request -> {}, "captureGraphPatch",
            propertyPatch("entry", "accessControlType", json.getNodeFactory().textNode("RBAC"))),
        Turn.tool("with-roles", request -> {
          var issue = feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().issues().getFirst();
          assertEquals("/properties/roles", issue.path());
          assertEquals("entry", issue.entityId());
          assertTrue(json.valueToTree(issue).path("details").path("valueMissing").asBoolean());
          assertTrue(delivered(request).contains("RBAC"));
          assertTrue(delivered(request).contains("thenRequired"));
          assertTrue(delivered(request).contains("roles"));
        }, "captureGraphPatch", patch("{\"operation\":\"UPDATE\",\"targetNodeId\":\"entry\",\"key\":\"accessControlType\",\"scalarValue\":\"RBAC\"},"
            + "{\"operation\":\"ADD\",\"targetNodeId\":\"entry\",\"key\":\"roles\",\"arrayValue\":[\"qip-viewer\"]}")));
    runAndAssertConsumed();
  }

  private void addProbe() {
    var nodes = new java.util.ArrayList<>(graph.nodes());
    nodes.add(new ChainPlanNode("probe", "repair-schema-probe", "Schema probe", null, 3,
        List.of(new PlanProperty("settings", settings().toString()))));
    graph = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, List.of(
        new ChainPlanEdge("entry-probe", "entry", "probe", null),
        new ChainPlanEdge("probe-response", "probe", "respond", null)));
    plans.put(conversation, graph);
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "input",
        new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    var context = contexts.get(conversation, CAPABILITY).orElseThrow();
    contexts.set(conversation, CAPABILITY, new GraphPatchExecutionContext("run", CAPABILITY, "", null,
        "", "2026.1", null, List.of(), graph, context.ownership().withAdditionalProperties(
            Map.of("repair-schema-probe", Set.of("settings"))), "attempt"));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode settings() {
    var value = json.createObjectNode();
    for (String key : List.of("first", "second", "third", "fourth")) {
      value.put(key, key);
    }
    value.put("mode", "permitted-option-159-" + "x".repeat(18));
    value.put("kind", "Map");
    value.putObject("mapping").put("name", "order");
    return value;
  }

  private String probePatch(com.fasterxml.jackson.databind.JsonNode value) {
    return propertyPatch("probe", "settings", value);
  }

  private String propertyPatch(String node, String key, com.fasterxml.jackson.databind.JsonNode value) {
    return patch("{\"operation\":\"UPDATE\",\"targetNodeId\":\"" + node
        + "\",\"key\":\"" + key + "\",\"value\":{},\"scalarValue\":"
        + json.valueToTree(value.isTextual() ? value.asText() : value.toString()) + "}");
  }

  private String patch(String properties) {
    return "{\"patch\":{\"patchId\":\"patch\",\"ownerCapabilityId\":\"" + CAPABILITY
        + "\",\"propertyPatches\":[" + properties + "],\"usedKnowledgeRefs\":[]}}";
  }

  private void install(Turn... turns) {
    script = new ScriptedStreamingChatModel(turns);
    doAnswer(call -> {
      script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  private org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult run() {
    var context = new SkillRunContext(conversation, CAPABILITY, CipKnowledgePackFixturePaths.PACK_DIR,
        SkillSubgraph.BUILD_CHAIN, 1, false, "", "", "run-" + conversation);
    runtime.runStreaming(context, workspace, CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(20));
    return runtime.run(context, workspace, CAPABILITY).await().atMost(Duration.ofSeconds(20));
  }

  private void runAndAssertConsumed() {
    var result = run();
    script.assertComplete();
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    var consumed = result.outputs().stream().filter(output -> output.type() == SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(output -> ((SkillArtifactPayload.ChainPlanGraphPayload) output.payload()).graph()).findFirst().orElseThrow();
    var accepted = captures.get(org.qubership.integration.platform.ai.compiler.capture.CaptureKey.capability(
        org.qubership.integration.platform.ai.compiler.capture.CaptureSlot.GRAPH_PATCH, conversation, CAPABILITY),
        GraphPatch.class).orElseThrow();
    for (var property : accepted.propertyPatches()) {
      assertTrue(consumed.nodes().stream().filter(node -> node.nodeId().equals(property.targetNodeId()))
          .flatMap(node -> node.properties().stream()).anyMatch(property.property()::equals));
    }
  }

  private static String delivered(ChatRequest request) {
    return request.messages().stream().map(message -> message instanceof ToolExecutionResultMessage tool ? tool.text()
        : message instanceof UserMessage user ? user.singleText() : "").reduce("", (a, b) -> a + "\n" + b);
  }
}
