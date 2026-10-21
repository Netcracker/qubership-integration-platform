package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class ScriptBodyRepairToolTest {

  @Test
  void mappingBatchRecoveryKeepsAValidatedResponseWhileRepairingTheRequest() {
    ChainPlanGraph base = bindMappingBatch("attempt-1");
    String failure = tool.repairScriptBodies(mappingBatch("bad-request", rejectedDateScript(), responseScript()));
    assertTrue(failure.contains("Groovy mapping:"), failure);
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR,
        CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
    assertEquals(base, planStore.get(CONVERSATION_ID).orElseThrow());

    assertThrows(CaptureValidationException.class, () -> tool.repairScriptBodies(
        mappingBatch("fixed-request", "exchange.in.body = [result: 'request']", successOnlyResponseScript())));
    GraphPatch patch = captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR,
        CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    String response = patch.propertyPatches().stream()
        .filter(item -> "response".equals(item.targetNodeId()) && "script".equals(item.property().key()))
        .findFirst().orElseThrow().property().value();
    assertEquals(responseScript().trim(), response);
    assertEquals(base, planStore.get(CONVERSATION_ID).orElseThrow());
    for (var scenario : List.of(new Object[] {201, "{\"id\":\"task-1\",\"errors\":[]}", false},
        new Object[] {400, "[{\"message\":\"Rejected\"}]", true},
        new Object[] {503, "Unavailable", true},
        new Object[] {200, "{\"success\":false,\"errors\":[{\"message\":\"Rejected\"}]}", true})) {
      var exchange = new MappingExchange((Integer) scenario[0], scenario[1]);
      var result = (java.util.Map<?, ?>) new groovy.lang.GroovyShell(
          new groovy.lang.Binding(java.util.Map.of("exchange", exchange))).evaluate(response);
      assertEquals("completeTask", result.get("commandType"));
      assertEquals("exec-1", result.get("executionId"));
      assertEquals(scenario[2], result.containsKey("error"), result.toString());
      if ((Boolean) scenario[2]) {
        assertEquals("SALESFORCE_TASK_CREATE_ERROR", ((java.util.Map<?, ?>) result.get("error")).get("code"));
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"attempt", "contract"})
  void mappingBatchRecoveryDoesNotReuseValidatedBodiesAfterInputsChange(String change) {
    bindMappingBatch("attempt-1");
    assertTrue(tool.repairScriptBodies(mappingBatch("bad-request", rejectedDateScript(), responseScript()))
        .contains("Groovy mapping:"));
    bindMappingBatch("attempt".equals(change) ? "attempt-2" : "attempt-1");
    if ("contract".equals(change)) {
      executionContextStore.set(CONVERSATION_ID, CAPABILITY_ID, executionContextStore
          .get(CONVERSATION_ID, CAPABILITY_ID).orElseThrow().withMappingGenerationContext("Revised mapping contract"));
    }
    assertThrows(CaptureValidationException.class, () -> tool.repairScriptBodies(
        mappingBatch("new-attempt", "exchange.in.body = [result: 'request']", successOnlyResponseScript())));
    GraphPatch patch = captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR,
        CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertTrue(patch.propertyPatches().stream().anyMatch(item -> "response".equals(item.targetNodeId())
        && "script".equals(item.property().key()) && successOnlyResponseScript().equals(item.property().value())));
  }

  private ChainPlanGraph bindMappingBatch(String attemptId) {
    var graph = new ChainPlanGraph("1.0", new ChainSection("Tasks", "Tasks"),
        List.of(new ChainPlanNode("request", "script", "Request", null, 1,
                List.of(new PlanProperty("mappingIntentId", "request-map"))),
            new ChainPlanNode("response", "script", "Response", null, 2,
                List.of(new PlanProperty("mappingIntentId", "response-map")))),
        List.of(new ChainPlanEdge("next", "request", "response", null)));
    var request = new MappingIntent("request-map", "entry", MappingPort.OUTPUT, "call", MappingPort.REQUEST,
        List.of(new MappingIntentRule(null, "$.result", "Build the request.", MappingRuleStatus.USER_DEFINED)), "SCRIPT");
    var response = new MappingIntent("response-map", "call", MappingPort.OUTPUT, "delivery", MappingPort.REQUEST,
        List.of(new MappingIntentRule(null, "$.commandType", "Use completeTask.", MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(null, "$.executionId", "Retain the input ID.", MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(null, "$.error", "Return the failure.", MappingRuleStatus.USER_DEFINED)), "SCRIPT");
    planStore.put(CONVERSATION_ID, graph);
    executionContextStore.set(CONVERSATION_ID, CAPABILITY_ID, new GraphPatchExecutionContext("batch-run",
        CAPABILITY_ID, null, null, null, null, identityMappingBrief().withMappingIntents(List.of(request, response)),
        List.of(), graph, GraphPatchOwnershipPolicy.denyAll(), attemptId));
    return graph;
  }

  private static ScriptBodyRepairCapture mappingBatch(String id, String request, String response) {
    return new ScriptBodyRepairCapture(id, List.of(new ScriptBodyEntry("request", request, List.of("$.result")),
        new ScriptBodyEntry("response", response, List.of("$.commandType", "$.executionId", "$.error"))),
        "Fill the request and response mappings.");
  }

  private static String rejectedDateScript() {
    return "exchange.in.body = [result: java.time.OffsetDateTime.parse('2026-10-19T00:30:00+03:00').toLocalDate()]";
  }

  private static String successOnlyResponseScript() {
    return "exchange.in.body = [commandType: 'completeTask', executionId: 'exec-1']";
  }

  private static String responseScript() {
    return """
        def raw = exchange.in.body
        def parsed = raw
        if (raw instanceof String) {
            try { parsed = new groovy.json.JsonSlurper().parseText(raw) }
            catch (Exception ignored) { parsed = null }
        }
        def response = parsed instanceof Map ? parsed : [:]
        def errors = parsed instanceof Collection ? parsed : response.errors
        def status = exchange.in.getHeader('CamelHttpResponseCode', Integer)
        def failed = (status != null && status >= 400) || response.success == false ||
            (errors instanceof Collection && !errors.isEmpty())
        def result = [commandType: 'completeTask', executionId: 'exec-1']
        if (failed) result.error = [code: 'SALESFORCE_TASK_CREATE_ERROR', message: raw.toString()]
        exchange.in.body = result
        """;
  }

  public static class MappingMessage {
    public Object body;
    private final int status;
    MappingMessage(int status, Object body) { this.status = status; this.body = body; }
    public Object getHeader(String name, Class<?> type) { return status; }
  }

  public static class MappingExchange {
    public final MappingMessage in;
    MappingExchange(int status, Object body) { in = new MappingMessage(status, body); }
  }

  @Test
  void explicitEditUpdatesOnlyTheSelectedFilledScript() {
    ChainPlanGraph base = bindFilledScriptEdit();
    assertThrows(CaptureValidationException.class, () -> tool.repairScriptBodies(
        new ScriptBodyRepairCapture("edit-script", List.of(new ScriptBodyEntry("validate-siteIds", "return 2")),
            "Edit the selected script")));
    GraphPatch patch = captureSession.get(
        CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
        GraphPatch.class).orElseThrow();
    assertEquals(1, patch.propertyPatches().size());
    assertEquals("validate-siteIds", patch.propertyPatches().getFirst().targetNodeId());
    assertEquals(org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation.UPDATE,
        patch.propertyPatches().getFirst().operation());
    ChainPlanGraph applied = new GraphPatchApplier().apply(base, patch).graph();
    assertEquals(List.of(new PlanProperty("script", "return 2")), applied.nodes().get(1).properties());
    assertEquals(base.nodes().get(2), applied.nodes().get(2));
    assertEquals(List.of(new PlanProperty("script", "return 1")), base.nodes().get(1).properties());
  }

  @Test
  void explicitEditRejectsAnUnselectedScript() {
    bindFilledScriptEdit();
    String result = tool.repairScriptBodies(new ScriptBodyRepairCapture("edit-extra",
        List.of(new ScriptBodyEntry("validate-siteIds", "return 2"),
            new ScriptBodyEntry("append-found-row", "return 3")), "Edit the selected script"));
    assertTrue(result.contains("targetNodeId is not allowed"), result);
  }

  @Test
  void explicitEditRejectsABlankReplacement() {
    bindFilledScriptEdit();
    String result = tool.repairScriptBodies(new ScriptBodyRepairCapture("edit-blank",
        List.of(new ScriptBodyEntry("validate-siteIds", " ")), "Edit the selected script"));
    assertTrue(result.contains("script body is blank"), result);
  }

  private ChainPlanGraph bindFilledScriptEdit() {
    ChainPlanGraph fixture = graphWithMissingScripts();
    ChainPlanGraph base = new ChainPlanGraph(fixture.schemaVersion(), fixture.chain(),
        fixture.nodes().stream().map(node -> "script".equals(node.type())
            ? new ChainPlanNode(node.nodeId(), node.type(), node.label(), node.parentNodeId(), node.order(),
                List.of(new PlanProperty("script", "validate-siteIds".equals(node.nodeId())
                    ? "return 1" : "return 'untouched'"))) : node).toList(), fixture.edges());
    executionContextStore.set(CONVERSATION_ID, CAPABILITY_ID,
        new GraphPatchExecutionContext("edit-run", CAPABILITY_ID, null, null, null, null, null,
            List.of(), base, GraphPatchOwnershipPolicy.denyAll(), "attempt", List.of("validate-siteIds"))
            .withExistingScriptBodyEdit(true));
    return base;
  }

  private static final String CONVERSATION_ID = "script-repair-conv";
  private static final String CAPABILITY_ID = "custom-script-generator";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  private CaptureSession captureSession;
  private ChainPlanStore planStore;
  private CaptureAttemptFeedbackStore feedbackStore;
  private GraphPatchExecutionContextStore executionContextStore;
  private CompilationArtifacts compilationArtifacts;
  private ScriptBodyRepairTool tool;

  @BeforeEach
  void setUp() {
    captureSession = new CaptureSession();
    planStore = new ChainPlanStore();
    feedbackStore = new CaptureAttemptFeedbackStore();
    executionContextStore = new GraphPatchExecutionContextStore();
    compilationArtifacts =
        new CompilationArtifacts(new InMemoryArtifactBlobStore(), MAPPER, Clock.systemUTC());
    CaptureRouter captureRouter = mock(CaptureRouter.class);
    when(captureRouter.routeFor(CAPABILITY_ID))
        .thenReturn(new CaptureRoute(CAPABILITY_ID, CaptureTool.REPAIR_SCRIPT_BODIES));
    tool =
        new ScriptBodyRepairTool(
            captureRouter,
            captureSession,
            planStore,
            new GeneratorReadinessEvaluator(),
            new GraphPatchApplier(),
            feedbackStore,
            executionContextStore,
            compilationArtifacts);
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, CAPABILITY_ID);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  @Test
  void repeatWithANewTraceIdIsBlockedBeforeScriptRepair() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());
    feedbackStore.beginScope(CONVERSATION_ID, CAPABILITY_ID, java.util.Map.of("inputs", "v1"));
    var invalid = new ScriptBodyRepairCapture("first", List.of(), "first attempt");
    assertTrue(tool.repairScriptBodies(invalid).contains("scripts"));
    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> tool.repairScriptBodies(new ScriptBodyRepairCapture("second", List.of(), "second attempt")));
    assertFalse(captureSession.isPresent(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID)));
  }

  @Test
  void deduplicatesDuplicateTargetNodeIdsKeepingLastScript() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-dup",
                        List.of(
                            new ScriptBodyEntry("validate-siteIds", "stale body"),
                            new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;"),
                            new ScriptBodyEntry(
                                "append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                        "Fill script bodies")));

    assertTrue(terminal.getMessage().contains("Script body repair patch captured"));
    GraphPatch patch = captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals(
        "const ids = exchange.body.siteIds;",
        patch.propertyPatches().stream()
            .filter(p -> "validate-siteIds".equals(p.targetNodeId()))
            .findFirst()
            .orElseThrow()
            .property()
            .value());
  }

  @Test
  void successfulCaptureTerminatesStreamingToolLoop() {
    assertTrue(
        new CaptureValidationException("x")
            instanceof io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution);
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.repairScriptBodies(
                new ScriptBodyRepairCapture(
                    "script-repair-terminal",
                    List.of(
                        new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;"),
                        new ScriptBodyEntry(
                            "append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                    "Fill script bodies")));
    assertTrue(
        captureSession.isPresent(
            CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID)));
  }

  @Test
  void capturesCompleteScriptBodyPatch() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-1",
                        List.of(
                            new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;"),
                            new ScriptBodyEntry(
                                "append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                        "Fill script bodies")));

    assertTrue(terminal.getMessage().contains("Script body repair patch captured"));
    assertTrue(terminal.getMessage().contains("Do not call repairScriptBodies again"));
    assertTrue(terminal.getMessage().contains("finish this turn"));
    GraphPatch patch =
        captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals(2, patch.propertyPatches().size());
    assertTrue(
        patch.propertyPatches().stream()
            .allMatch(propertyPatch -> "script".equals(propertyPatch.property().key())));
  }

  @Test
  void repairsOnlyScriptsInTheActiveEditPlan() {
    ChainPlanGraph graph = graphWithMissingScripts();
    planStore.put(CONVERSATION_ID, graph);
    executionContextStore.set(
        CONVERSATION_ID,
        CAPABILITY_ID,
        new GraphPatchExecutionContext(
            "edit-run",
            CAPABILITY_ID,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            graph,
            GraphPatchOwnershipPolicy.denyAll(),
            "attempt-1",
            List.of("append-found-row")));

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-scoped",
                        List.of(
                            new ScriptBodyEntry(
                                "append-found-row",
                                "exchange.body.rows.push(exchange.body.item);")),
                        "Fill the new catch script")));

    assertTrue(terminal.getMessage().contains("Script body repair patch captured"));
    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertEquals(
        List.of("append-found-row"),
        patch.propertyPatches().stream().map(patchItem -> patchItem.targetNodeId()).toList());
  }

  @Test
  void duplicateCaptureThrowsTerminalValidationExceptionAndPreservesPatch() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    CaptureValidationException first =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-1",
                        List.of(
                            new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;"),
                            new ScriptBodyEntry(
                                "append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                        "Fill script bodies")));
    assertTrue(first.getMessage().contains("Script body repair patch captured"));
    GraphPatch firstPatch = captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();

    CaptureValidationException ex =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-2",
                        List.of(
                            new ScriptBodyEntry("validate-siteIds", "overwrite body"),
                            new ScriptBodyEntry("append-found-row", "overwrite other")),
                        "Duplicate repair")));

    assertTrue(ex.getMessage().contains("already captured"));
    assertTrue(ex.getMessage().contains("finish this turn without further tool calls"));
    GraphPatch preserved = captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals(firstPatch.patchId(), preserved.patchId());
    assertEquals(
        "const ids = exchange.body.siteIds;",
        preserved.propertyPatches().stream()
            .filter(p -> "validate-siteIds".equals(p.targetNodeId()))
            .findFirst()
            .orElseThrow()
            .property()
            .value());
  }

  @Test
  void rejectsMissingScriptEntry() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-repair-1",
                List.of(new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;")),
                "Partial repair"));

    assertTrue(result.contains("missing scripts for node ids append-found-row"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsExtraScriptEntry() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-repair-1",
                List.of(
                    new ScriptBodyEntry("validate-siteIds", "const ids = exchange.body.siteIds;"),
                    new ScriptBodyEntry("append-found-row", "exchange.body.rows.push(exchange.body.item);"),
                    new ScriptBodyEntry("not-missing", "return null;")),
                "Extra repair"));

    assertTrue(result.contains("targetNodeId is not allowed: not-missing"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsBlankScriptBody() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-repair-1",
                List.of(
                    new ScriptBodyEntry("validate-siteIds", " "),
                    new ScriptBodyEntry("append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                "Blank repair"));

    assertTrue(result.contains("script body is blank for node ids validate-siteIds"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsOmittedPlaceholderScriptBody() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-repair-1",
                List.of(
                    new ScriptBodyEntry("validate-siteIds", "<script body omitted, 379 chars>"),
                    new ScriptBodyEntry("append-found-row", "exchange.body.rows.push(exchange.body.item);")),
                "Placeholder repair"));

    assertTrue(result.contains("script body is blank for node ids validate-siteIds"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsEmptyScripts() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture("script-repair-1", List.of(), "Empty repair"));

    assertTrue(result.contains("scripts are required"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void repeatedValidationTerminatesStream() {
    planStore.put(CONVERSATION_ID, graphWithMissingScripts());

    tool.repairScriptBodies(new ScriptBodyRepairCapture("script-repair-1", List.of(), "Empty repair"));
    CaptureValidationException failure =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-repair-1", List.of(), "Empty repair again")));

    assertTrue(failure.getMessage().contains("Repeated script body repair validation failure"));
    assertTrue(failure.getMessage().contains("scripts are required"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void mappingGrabScriptFailsClosed() {
    bindMappingScriptRepair();

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-map-grab",
                List.of(
                    new ScriptBodyEntry(
                        "transform-map-init",
                        "@Grab('foo:bar:1')\ndef x = 1\n",
                        List.of("$.orderId"))),
                "Fill mapping script"));

    assertTrue(result.contains("Groovy mapping:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void mappingMissingCoverageFailsClosed() {
    bindMappingScriptRepair();

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-map-no-coverage",
                List.of(
                    new ScriptBodyEntry(
                        "transform-map-init", "target['orderId'] = source['orderId']\n")),
                "Fill mapping script"));

    assertTrue(result.contains("Mapping parity:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void mappingIdentityScriptWithCoveragePasses() {
    bindMappingScriptRepair();

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-map-ok",
                        List.of(
                            new ScriptBodyEntry(
                                "transform-map-init",
                                "target['orderId'] = source['orderId']\n",
                                List.of("$.orderId"))),
                        "Fill mapping script")));

    assertTrue(terminal.getMessage().contains("Script body repair patch captured"));
    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertTrue(
        patch.propertyPatches().stream()
            .anyMatch(propertyPatch -> "script".equals(propertyPatch.property().key())));
    assertTrue(
        patch.propertyPatches().stream()
            .anyMatch(
                propertyPatch -> "mappingCoverage".equals(propertyPatch.property().key())));
    assertTrue(
        patch.propertyPatches().stream()
            .allMatch(
                propertyPatch ->
                    "script".equals(propertyPatch.property().key())
                        || "mappingCoverage".equals(propertyPatch.property().key())));
    assertEquals(
        "map-init",
        MappingExecutionSite.mappingIntentId(
            graphWithMappingScript().nodes().getFirst()));
  }

  @Test
  void directBodyCopyProjectsApprovedScriptInsteadOfModelHeaderScript() throws Exception {
    bindDirectMappingRepair();

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.repairScriptBodies(
                new ScriptBodyRepairCapture(
                    "direct-copy",
                    List.of(
                        new ScriptBodyEntry(
                            "transform-map-init",
                            "exchange.in.headers.put('orderId', exchange.in.body.orderId)",
                            List.of("$.orderId"))),
                    "Map the approved body field")));

    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    String script =
        patch.propertyPatches().stream()
            .filter(property -> "script".equals(property.property().key()))
            .findFirst()
            .orElseThrow()
            .property()
            .value();
    assertTrue(script.contains("exchange.in.body = ['orderId': source['orderId']]"));
    assertFalse(script.contains("headers"));
  }

  @Test
  void directBodyCopyProjectsWhenHttpSchemasAreUnknown() throws Exception {
    bindDirectMappingRepair(true);

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.repairScriptBodies(
                new ScriptBodyRepairCapture(
                    "direct-copy-unknown",
                    List.of(
                        new ScriptBodyEntry(
                            "transform-map-init",
                            "exchange.in.headers.put('orderId', exchange.in.body.orderId)",
                            List.of("$.orderId"))),
                    "Map the approved body field")));

    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertTrue(
        patch.propertyPatches().stream()
            .anyMatch(
                property ->
                    "script".equals(property.property().key())
                        && property.property().value().contains("exchange.in.body = ['orderId'")));
  }

  @Test
  void mappingRepairRejectsUnexpectedCoverageWithoutMutatingTheContract() {
    bindMappingScriptRepair();

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-map-extra",
                List.of(
                    new ScriptBodyEntry(
                        "transform-map-init",
                        "target['orderId'] = source['orderId']\n",
                        List.of("$.orderId", "$.extra"))),
                "Fill mapping script"));

    assertTrue(result.contains("unexpected"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
    assertEquals(
        "map-init",
        MappingExecutionSite.mappingIntentId(
            planStore.get(CONVERSATION_ID).orElseThrow().nodes().getFirst()));
  }

  @Test
  void mappingRepairReplacesScriptAndCoverageWithoutMutatingSiteOwnership() {
    bindMappingScriptRepair();

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.repairScriptBodies(
                new ScriptBodyRepairCapture(
                    "script-map-replace",
                    List.of(
                        new ScriptBodyEntry(
                            "transform-map-init",
                            "target['orderId'] = source['orderId']\n",
                            List.of("$.orderId"))),
                    "Replace mapping script")));

    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertTrue(patch.nodePatches().isEmpty());
    assertTrue(patch.edgePatches().isEmpty());
    assertTrue(
        patch.propertyPatches().stream()
            .allMatch(
                propertyPatch ->
                    "script".equals(propertyPatch.property().key())
                        || "mappingCoverage".equals(propertyPatch.property().key())));
    assertTrue(
        planStore.get(CONVERSATION_ID).orElseThrow().nodes().stream()
            .anyMatch(
                node ->
                    "transform-map-init".equals(node.nodeId())
                        && "map-init".equals(MappingExecutionSite.mappingIntentId(node))));
  }

  @Test
  void mappingRepairRejectsUnexpectedCoverageWithoutChangingTheIntent() {
    bindMappingScriptRepair();

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-map-extra",
                List.of(
                    new ScriptBodyEntry(
                        "transform-map-init",
                        "target['orderId'] = source['orderId']\n",
                        List.of("$.orderId", "$.extra"))),
                "Extra coverage"));

    assertTrue(result.contains("unexpected="));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
    assertEquals(
        "map-init",
        MappingExecutionSite.mappingIntentId(
            planStore.get(CONVERSATION_ID).orElseThrow().nodes().getFirst()));
  }

  @Test
  void requestHopRepairStripsResponseKeepPathsFromStoredCoverage() throws Exception {
    bindRequestHopMappingRepair();

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.repairScriptBodies(
                    new ScriptBodyRepairCapture(
                        "script-request-hop",
                        List.of(
                            new ScriptBodyEntry(
                                "transform-create-task",
                                requestHopScript(),
                                List.of(
                                    "Subject",
                                    "Description",
                                    "$.response.executionId",
                                    "$.response.orderId"))),
                        "Fill request hop mapping")));

    assertTrue(terminal.getMessage().contains("Script body repair patch captured"));
    GraphPatch patch =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    String coverageJson =
        patch.propertyPatches().stream()
            .filter(
                propertyPatch ->
                    MappingExecutionSite.MAPPING_COVERAGE_PROPERTY.equals(
                        propertyPatch.property().key()))
            .findFirst()
            .orElseThrow()
            .property()
            .value();
    assertTrue(coverageJson.contains("Subject"));
    assertTrue(coverageJson.contains("Description"));
    assertFalse(coverageJson.contains("$.response"));
    String script =
        patch.propertyPatches().stream()
            .filter(propertyPatch -> "script".equals(propertyPatch.property().key()))
            .findFirst()
            .orElseThrow()
            .property()
            .value();
    assertTrue(script.contains("response.executionId"));
    assertTrue(script.contains("response.orderId"));
  }

  @Test
  void requestHopRepairStillReportsMissingHopBodyField() throws Exception {
    bindRequestHopMappingRepair();

    String result =
        tool.repairScriptBodies(
            new ScriptBodyRepairCapture(
                "script-request-hop-missing",
                List.of(
                    new ScriptBodyEntry(
                        "transform-create-task",
                        requestHopScript(),
                        List.of("Description", "$.response.executionId", "$.response.orderId"))),
                "Fill request hop mapping"));

    assertTrue(result.contains("missing="));
    assertTrue(result.contains("Subject"));
    assertFalse(result.contains("$.response"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  private void bindMappingScriptRepair() {
    ChainPlanGraph graph = graphWithMappingScript();
    planStore.put(CONVERSATION_ID, graph);
    executionContextStore.set(
        CONVERSATION_ID,
        CAPABILITY_ID,
        new GraphPatchExecutionContext(
            "map-run",
            CAPABILITY_ID,
            null,
            null,
            null,
            null,
            identityMappingBrief(),
            List.of(),
            graph,
            GraphPatchOwnershipPolicy.denyAll(),
            "attempt-1"));
  }

  private void bindDirectMappingRepair() throws Exception {
    bindDirectMappingRepair(false);
  }

  private void bindDirectMappingRepair(boolean unknownSchema) throws Exception {
    ChainPlanGraph graph = graphWithMappingScript();
    JsonNode body =
        unknownSchema ? null : MAPPER.readTree(
            """
            {"type":"object","properties":{"orderId":{"type":"string"}}}
            """);
    MappingEnvelope envelope =
        new JsonSchemaMessageSchemaFactory(MAPPER)
            .fromSides(
                new MappingSchemaSide(
                    "1", "trigger-http", "op", MappingPort.OUTPUT, "application/json", null,
                    "source-hash", "test", body),
                new MappingSchemaSide(
                    "1", "call-1", "op", MappingPort.REQUEST, "application/json", null,
                    "target-hash", "test", body))
            .withMappingIntentId("map-init");
    CompilationArtifacts.Revision revision =
        compilationArtifacts.append(
            new CompilationArtifacts.AppendCommand(
                CONVERSATION_ID,
                CompilationArtifacts.Kind.MAPPING_ENVELOPE,
                "1",
                "test",
                "1",
                envelope,
                List.of(),
                null));
    planStore.put(CONVERSATION_ID, graph);
    executionContextStore.set(
        CONVERSATION_ID,
        CAPABILITY_ID,
        new GraphPatchExecutionContext(
            "map-run",
            CAPABILITY_ID,
            null,
            null,
            null,
            null,
            identityMappingBrief(),
            List.of(revision.reference()),
            graph,
            GraphPatchOwnershipPolicy.denyAll(),
            "attempt-1"));
  }

  private static RequirementBrief identityMappingBrief() {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map orderId",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-init",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "call-1",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)),
                    "SCRIPT")));
  }

  private void bindRequestHopMappingRepair() throws Exception {
    ChainPlanGraph graph = graphWithRequestHopScript();
    MappingEnvelope envelope =
        requestHopEnvelope().withMappingIntentId("request-onTaskStart-to-createTask");
    CompilationArtifacts.Revision revision =
        compilationArtifacts.append(
            new CompilationArtifacts.AppendCommand(
                CONVERSATION_ID,
                CompilationArtifacts.Kind.MAPPING_ENVELOPE,
                "1",
                "test",
                "1",
                envelope,
                List.of(),
                null));
    planStore.put(CONVERSATION_ID, graph);
    executionContextStore.set(
        CONVERSATION_ID,
        CAPABILITY_ID,
        new GraphPatchExecutionContext(
            "map-run",
            CAPABILITY_ID,
            null,
            null,
            null,
            null,
            requestHopBrief(),
            List.of(revision.reference()),
            graph,
            GraphPatchOwnershipPolicy.denyAll(),
            "attempt-1"));
  }

  private static RequirementBrief requestHopBrief() {
    return new RequirementBrief(
            "Tasks",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map onTaskStart to createTask",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(List.of(requestHopIntent()));
  }

  private static MappingIntent requestHopIntent() {
    return new MappingIntent(
        "request-onTaskStart-to-createTask",
        "onTaskStart",
        MappingPort.OUTPUT,
        "createTask",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule("name", "Subject", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "taskId", "Description.taskId", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "executionId",
                "responseContext.executionId",
                "Keep for the response.",
                MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "orderId",
                "responseContext.orderId",
                "Keep for the response.",
                MappingRuleStatus.USER_DEFINED)),
        "SCRIPT");
  }

  private static MappingEnvelope requestHopEnvelope() throws Exception {
    JsonNode onTaskStart =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "taskId": { "type": "string" },
                "executionId": { "type": "string" },
                "orderId": { "type": "string" }
              }
            }
            """);
    JsonNode createTask =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "Subject": { "type": "string" },
                "Description": { "type": "string" },
                "Priority": { "type": "string" },
                "Status": { "type": "string" },
                "ActivityDate": { "type": "string" }
              }
            }
            """);
    return new JsonSchemaMessageSchemaFactory(MAPPER)
        .fromSides(
            mappingSide("onTaskStart", MappingPort.OUTPUT, onTaskStart),
            mappingSide("createTask", MappingPort.REQUEST, createTask));
  }

  private static MappingSchemaSide mappingSide(
      String serviceCallId, MappingPort direction, JsonNode schema) {
    return new MappingSchemaSide(
        "1",
        serviceCallId,
        "op-1",
        direction,
        "application/json",
        null,
        "sha-test",
        "test-provenance",
        schema);
  }

  private static String requestHopScript() {
    return """
        target['Subject'] = source['name']
        target['Description'] = source['taskId']
        response.executionId = source['executionId']
        response.orderId = source['orderId']
        """;
  }

  private static ChainPlanGraph graphWithRequestHopScript() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Tasks", "Tasks"),
        List.of(
            new ChainPlanNode(
                "transform-create-task",
                "script",
                "Map",
                null,
                1,
                List.of(
                    new PlanProperty("mappingIntentId", "request-onTaskStart-to-createTask"),
                    new PlanProperty("script", "")))),
        List.of());
  }

  private static ChainPlanGraph graphWithMappingScript() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "transform-map-init",
                "script",
                "Map",
                null,
                1,
                List.of(
                    new PlanProperty("mappingIntentId", "map-init"),
                    new PlanProperty("script", "")))),
        List.of());
  }

  private static ChainPlanGraph graphWithMissingScripts() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Bulk lookup", "Bulk lookup"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("validate-siteIds", "script", "Validate siteIds", null, 2, List.of()),
            new ChainPlanNode(
                "append-found-row",
                "script",
                "Append found row",
                null,
                3,
                List.of(new PlanProperty("script", "")))),
        List.of(
            new ChainPlanEdge("edge-1", "http-trigger-1", "validate-siteIds", null),
            new ChainPlanEdge("edge-2", "validate-siteIds", "append-found-row", null)));
  }
}
