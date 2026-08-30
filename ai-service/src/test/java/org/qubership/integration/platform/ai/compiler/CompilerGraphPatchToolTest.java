package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Attribute;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.StringType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class CompilerGraphPatchToolTest {

  private static final String CONVERSATION_ID = "conv-patch-1";
  private static final String CAPABILITY_ID = "cip-error-handling-generator";
  private static final String SCRIPT_CAPABILITY_ID = "cip-script-generator";
  private static final String SECURITY_CAPABILITY_ID = "cip-security-generator";
  private static final String SERVICE_CALL_CAPABILITY_ID = "cip-service-call-generator";
  private static final String TRANSFORMATION_CAPABILITY_ID = "cip-transformation-generator";

  private static CompilerGeneratorPolicy policy;

  private CaptureSession captureSession;
  private ChainPlanStore planStore;
  private DeterministicElementSchemaService schemaService;
  private CaptureAttemptFeedbackStore feedbackStore;
  private GraphPatchExecutionContextStore executionContextStore;
  private CaptureRouter captureRouter;
  private KnowledgeCitationResolver citationResolver;
  private CompilerGraphPatchTool tool;
  private ObjectMapper objectMapper;
  private CompilationArtifacts compilationArtifacts;

  @BeforeAll
  static void loadPolicy() throws Exception {
    policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
  }

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    compilationArtifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            objectMapper.copy().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
            java.time.Clock.systemUTC());
    captureSession = new CaptureSession();
    planStore = new ChainPlanStore();
    schemaService = DeterministicElementSchemaService.createForUnitTests(objectMapper);
    feedbackStore = new CaptureAttemptFeedbackStore();
    executionContextStore = new GraphPatchExecutionContextStore();
    captureRouter = mock(CaptureRouter.class);
    citationResolver = mock(KnowledgeCitationResolver.class);
    when(citationResolver.resolve(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());
    when(captureRouter.routeFor(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation ->
                new CaptureRoute(invocation.getArgument(0), CaptureTool.CAPTURE_GRAPH_PATCH));
    QipKnowledgePackRepository packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.loadCompilerGeneratorPolicy()).thenReturn(policy);
    tool =
        new CompilerGraphPatchTool(
            captureSession,
            planStore,
            schemaService,
            objectMapper,
            feedbackStore,
            packRepository,
            new GeneratorReadinessEvaluator(schemaService, objectMapper),
            new GraphPatchApplier(),
            new CaptureRepairMessageBuilder(schemaService),
            executionContextStore,
            captureRouter,
            citationResolver,
            compilationArtifacts);
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, CAPABILITY_ID);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
    executionContextStore.clear();
  }

  @Test
  void rejectsCaptureGraphPatchWhenCapabilityRouteIsConfiguredTriggerSet() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, "cip-trigger-generator");
    when(captureRouter.routeFor("cip-trigger-generator"))
        .thenReturn(
            new CaptureRoute("cip-trigger-generator", CaptureTool.CAPTURE_CONFIGURED_TRIGGER_SET));
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "wrong-tool",
            "cip-trigger-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "contextPath",
                    JsonNodeFactory.instance.textNode("/greetings"))),
            List.of(),
            List.of(),
            "mistaken graph patch");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("captureConfiguredTriggerSet"));
    assertTrue(result.contains("not valid for cip-trigger-generator"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, "cip-trigger-generator"),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void resolvesCapabilityFromToolSinkWhenWorkerHasNoMdc() {
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
    ToolSession.bind(CONVERSATION_ID);
    ToolInvocationSink.bind(event -> {}, "skill:" + CAPABILITY_ID, CONVERSATION_ID);
    try {
      assertEquals(CAPABILITY_ID, CompilerGraphPatchTool.resolveCapabilityId());
    } finally {
      ToolInvocationSink.unbind();
      ToolSession.clear();
    }
  }

  @Test
  void capturesValidPatch() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "eh-test",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No changes required");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored = captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals("eh-test", stored.patchId());
    assertEquals(CAPABILITY_ID, stored.ownerCapabilityId());
  }

  @Test
  void storesResolvedKnowledgeCitationFromReferenceId() {
    QipKnowledgeCitation citation =
        new QipKnowledgeCitation(
            "CIP:LEL-000142",
            QipKnowledgeRefType.KNOWLEDGE_OBJECT,
            "language/element-relationships.md#pattern-trigger-try-catch-finally-2",
            new QipKnowledgePackVersion("1.0.0", "1.0.0"),
            "Pattern: Trigger to Try-Catch-Finally-2");
    when(citationResolver.resolve(CONVERSATION_ID, List.of("CIP:LEL-000142")))
        .thenReturn(List.of(citation));
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "eh-cited",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("CIP:LEL-000142"),
            "No changes required");

    assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    GraphPatch stored =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertEquals(citation, stored.usedKnowledgeRefs().getFirst());
  }

  @Test
  void recoversEhAlreadyExistsAddIntoCatchPropertyEnrich() {
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("GeographicSite.Proxy.GetById", "Proxy"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
                new ChainPlanNode("try-shell", "try-2", "Try", "eh-wrap", null, List.of()),
                new ChainPlanNode("catch-shell", "catch-2", "Catch", "eh-wrap", null, List.of()),
                new ChainPlanNode(
                    "call-1", "service-call", "Service call", "try-shell", null, List.of())),
            List.of());
    planStore.put(CONVERSATION_ID, baseGraph);

    GraphPatchCapture wrapAttempt =
        new GraphPatchCapture(
            "add-try-catch-wrapper-atomic",
            CAPABILITY_ID,
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("try-shell", "try-2", "Try", "eh-wrap", null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("catch-shell", "catch-2", "Catch", "eh-wrap", null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Greenfield EH wrap when captureChainPlan has no EH nodes",
            false);

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(wrapAttempt));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored =
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertEquals("eh-recover-catch-2-mandatory-properties", stored.patchId());
    assertEquals(2, stored.propertyPatches().size());
    assertEquals("catch-shell", stored.propertyPatches().get(0).targetNodeId());
    assertEquals("exception", stored.propertyPatches().get(0).property().key());
  }

  @Test
  void notApplicableTrueWithEmptyPatchesAcceptsAndTerminatesStream() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SERVICE_CALL_CAPABILITY_ID);
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "http-service-call-catalog-binding",
            SERVICE_CALL_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No service-call nodes are present in the chain, thus no service-call configurations are needed.",
            true);

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(terminal.getMessage().contains("notApplicable"));
    assertTrue(
        terminal
            instanceof io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution);
    GraphPatch stored =
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SERVICE_CALL_CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertTrue(stored.nodePatches() == null || stored.nodePatches().isEmpty());
    assertTrue(stored.edgePatches() == null || stored.edgePatches().isEmpty());
    assertTrue(stored.propertyPatches() == null || stored.propertyPatches().isEmpty());
    assertTrue(stored.chainPatches() == null || stored.chainPatches().isEmpty());
  }

  @Test
  void notApplicableTrueWithNonEmptyPatchesRejectsFailClosed() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SERVICE_CALL_CAPABILITY_ID);
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "invented-binding",
            SERVICE_CALL_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationSystemId",
                    JsonNodeFactory.instance.textNode("sys-1"))),
            List.of(),
            List.of(),
            "Invented patch while claiming not applicable",
            true);

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("notApplicable=true requires empty"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SERVICE_CALL_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void notApplicableAbsentKeepsNormalPatchPath() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-http-trigger-rbac",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "accessControlType",
                    JsonNodeFactory.instance.textNode("RBAC"))),
            List.of(),
            List.of(),
            "RBAC patch",
            null);

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored =
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID),
                GraphPatch.class)
            .orElseThrow();
    assertEquals("RBAC", stored.propertyPatches().get(0).property().value());
  }

  @Test
  void successfulCaptureTerminatesStreamingToolLoop() {
    // CaptureValidationException implements PreventsErrorHandlerExecution so quarkus-langchain4j
    // aborts the agent stream immediately. Without this, harvest waits for an LLM end-turn that
    // may never arrive (live hang after captureGraphPatch under CaptureRepairRunner).
    assertTrue(
        new CaptureValidationException("x")
            instanceof io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution);

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "eh-terminal",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Terminal accept");

    assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));
    assertTrue(
        captureSession.isPresent(
            CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID)));
  }

  @Test
  void capturesStringPropertyValueWithoutExtraQuotes() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-http-trigger-rbac",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "accessControlType",
                    JsonNodeFactory.instance.textNode("RBAC"))),
            List.of(),
            List.of(),
            "RBAC patch");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored = captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals("RBAC", stored.propertyPatches().get(0).property().value());
  }

  @Test
  void capturesStructuredArrayPropertyValue() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-http-trigger-rbac",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "roles",
                    objectMapper.valueToTree(List.of("qip-viewer")))),
            List.of(),
            List.of(),
            "RBAC patch");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored = captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals("[\"qip-viewer\"]", stored.propertyPatches().get(0).property().value());
  }

  @Test
  void capturesStructuredObjectPropertyValue() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "object-prop",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "script-1",
                    "headers",
                    objectMapper.valueToTree(Map.of("X-Trace", "1")))),
            List.of(),
            List.of(),
            "Header patch");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored = captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertEquals("{\"X-Trace\":\"1\"}", stored.propertyPatches().get(0).property().value());
  }

  @Test
  void capturesNullPropertyValue() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "null-prop",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD, "http-trigger-1", "optionalField", null)),
            List.of(),
            List.of(),
            "Null value patch");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored = captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).orElseThrow();
    assertNull(stored.propertyPatches().get(0).property().value());
  }

  @Test
  void rejectsInvalidHttpMethodRestrictArrayInPropertyPatch() {
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "HTTP Trigger", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "trigger-method",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "httpMethodRestrict",
                    objectMapper.createArrayNode().add("GET"))),
            List.of(),
            List.of(),
            "Invalid method patch");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Invalid property value"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void repeatedConversionFailureTerminatesStream() {
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "HTTP Trigger", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "trigger-method",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "httpMethodRestrict",
                    objectMapper.createArrayNode().add("GET"))),
            List.of(),
            List.of(),
            "Invalid method patch");

    String first = tool.captureGraphPatch(patch);
    assertTrue(first.contains("Invalid property value"));

    CaptureValidationException failure =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(failure.getMessage().contains("Repeated graph patch conversion failure"));
    assertTrue(failure.getMessage().contains("Invalid property value"));
  }

  @Test
  void rejectsWrongOwnerCapabilityId() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "eh-test",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "test");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("ownerCapabilityId must be"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void emptyPatchWithMissingOwnedCronIsCorrectableNotStored() {
    String quartzSkill = "cip-quartz-scheduler-generator";
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("dual-trigger", "Dual Trigger"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1", "quartz-scheduler", "Hourly", null, null, List.of())),
            List.of());
    planStore.put(CONVERSATION_ID, baseGraph);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, quartzSkill);
    executionContextStore.set(
        new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
            "run-1",
            quartzSkill,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            baseGraph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of(),
                Set.of(),
                Map.of("quartz-scheduler", Set.of("cron", "deleteJob"))),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "quartz-empty",
            quartzSkill,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No schedule intent");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("quartz-scheduler-1"));
    assertTrue(result.contains("cron"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, quartzSkill),
                GraphPatch.class)
            .isEmpty());
    assertTrue(
        feedbackStore
            .lastPatchFailure(CONVERSATION_ID, quartzSkill)
            .filter(failure -> failure.kind() == CaptureFailureKind.VALIDATION)
            .isPresent());
  }

  @Test
  void placeholderCronValueIsCorrectableNotStored() {
    String quartzSkill = "cip-quartz-scheduler-generator";
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("dual-trigger", "Dual Trigger"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1", "quartz-scheduler", "Hourly", null, null, List.of())),
            List.of());
    planStore.put(CONVERSATION_ID, baseGraph);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, quartzSkill);
    executionContextStore.set(
        new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
            "run-1",
            quartzSkill,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            baseGraph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of(),
                Set.of(),
                Map.of("quartz-scheduler", Set.of("cron", "deleteJob"))),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "quartz-placeholder-cron",
            quartzSkill,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "quartz-scheduler-1",
                    "cron",
                    JsonNodeFactory.instance.textNode(
                        OwnedSchemaRequiredPropertyGate.PLACEHOLDER_VALUE))),
            List.of(),
            List.of(),
            "Placeholder cron must soft-reject");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("quartz-scheduler-1"));
    assertTrue(result.contains("cron"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, quartzSkill),
                GraphPatch.class)
            .isEmpty());
    assertTrue(
        feedbackStore
            .lastPatchFailure(CONVERSATION_ID, quartzSkill)
            .filter(failure -> failure.kind() == CaptureFailureKind.CONVERSION)
            .isPresent());
  }

  @Test
  void emptyPatchWithCronAlreadyPresentIsAccepted() {
    String quartzSkill = "cip-quartz-scheduler-generator";
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("dual-trigger", "Dual Trigger"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1",
                    "quartz-scheduler",
                    "Hourly",
                    null,
                    null,
                    List.of(new PlanProperty("cron", "0 0 * * * ?")))),
            List.of());
    planStore.put(CONVERSATION_ID, baseGraph);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, quartzSkill);
    executionContextStore.set(
        new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
            "run-1",
            quartzSkill,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            baseGraph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of(),
                Set.of(),
                Map.of("quartz-scheduler", Set.of("cron", "deleteJob"))),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "quartz-empty-ok",
            quartzSkill,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Cron already present; empty patch is a true no-op");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));
    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    GraphPatch stored =
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, quartzSkill),
                GraphPatch.class)
            .orElseThrow();
    assertEquals("quartz-empty-ok", stored.patchId());
    assertTrue(feedbackStore.lastPatchFailure(CONVERSATION_ID, quartzSkill).isEmpty());
  }

  @Test
  void rejectsPatchOutsidePinnedOwnershipEnvelope() {
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "HTTP Trigger", null, null, List.of())),
            List.of());
    planStore.put(
        CONVERSATION_ID,
        baseGraph);
    String timeoutSkill = "cip-timeout-generator";
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, timeoutSkill);
    executionContextStore.set(
        new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
            "run-1",
            timeoutSkill,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            baseGraph,
            GraphPatchOwnershipPolicy.denyAll(),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "timeout-owner",
            timeoutSkill,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "contextPath",
                    JsonNodeFactory.instance.textNode("/secure"))),
            List.of(),
            List.of(),
            "Unsupported property for timeout ownership");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("ownership"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, timeoutSkill),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void rejectsNullPatch() {
    String result = tool.captureGraphPatch(null);

    assertTrue(result.contains("patch is required"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsNodePatchMissingOperation() {
    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-hello-rbac",
            CAPABILITY_ID,
            List.of(new NodePatch(null, null, null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "RBAC patch");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Invalid graph patch shape"));
    assertTrue(result.contains("nodePatches[0].operation is required"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsAddNodeWithoutNodeId() {
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Greetings", "Greeting"),
            List.of(new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of())),
            List.of()));
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SCRIPT_CAPABILITY_ID);

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-missing-node-id",
            SCRIPT_CAPABILITY_ID,
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(null, "script", "Script", null, 2, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add script");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Invalid graph patch shape"));
    assertTrue(result.contains("node.nodeId is required for ADD"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsIncompleteScriptPatchWhenBaseGraphHasEmptyScriptBody() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SCRIPT_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of()),
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-empty-rationale",
            SCRIPT_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No script changes");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("script body"));
    assertTrue(result.contains("script-1"));
    assertTrue(result.contains("propertyPatches"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID), GraphPatch.class).isEmpty());
    assertTrue(
        feedbackStore
            .lastPatchFailure(CONVERSATION_ID, SCRIPT_CAPABILITY_ID)
            .filter(failure -> failure.kind() == CaptureFailureKind.VALIDATION)
            .isPresent());
  }

  @Test
  void capturesCompleteScriptPatchWhenBaseGraphHasEmptyScriptBody() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SCRIPT_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of()),
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-body",
            SCRIPT_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "script-1",
                    "script",
                    JsonNodeFactory.instance.textNode("exchange.setProperty('lang', 'ru');"))),
            List.of(),
            List.of(),
            "Filled script body");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID), GraphPatch.class).isPresent());
  }

  @Test
  void rejectsScriptPropertyPatchFromNonScriptGenerator() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SECURITY_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "exchange.setProperty('lang', 'ru');")))),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "overwrite-script",
            SECURITY_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.UPDATE,
                    "script-1",
                    "script",
                    JsonNodeFactory.instance.textNode("<script body omitted, 379 chars>"))),
            List.of(),
            List.of(),
            "Accidental script overwrite");

    CaptureValidationException ex =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(ex.getMessage().contains("Property patch for key 'script' is only allowed for cip-script-generator"));
    assertTrue(ex.getMessage().contains("Omit key 'script'"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SECURITY_CAPABILITY_ID), GraphPatch.class).isEmpty());
    assertTrue(feedbackStore.lastPatchFailure(CONVERSATION_ID, SECURITY_CAPABILITY_ID).isPresent());
    assertEquals(
        CaptureFailureKind.VALIDATION,
        feedbackStore.lastPatchFailure(CONVERSATION_ID, SECURITY_CAPABILITY_ID).orElseThrow().kind());
  }

  @Test
  void rejectsScriptPropertyPatchFromErrorHandlingGenerator() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("safe-inventory", "SafeInventory"),
            List.of(
                new ChainPlanNode(
                    "catch-2", "catch-2", "Catch", "try-catch-finally-2", null, List.of()),
                new ChainPlanNode(
                    "catch-script", "script", "Error response", "catch-2", null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "eh-with-script-body",
            CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "catch-2",
                    "exception",
                    JsonNodeFactory.instance.textNode("java.lang.Exception")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "catch-2",
                    "priority",
                    JsonNodeFactory.instance.numberNode(0)),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "catch-script",
                    "script",
                    JsonNodeFactory.instance.textNode(
                        "exchange.setProperty('CamelHttpResponseCode', 500);"))),
            List.of(),
            List.of(),
            "Adding mandatory properties for catch-2 and a script for corporate error response.");

    CaptureValidationException ex =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(ex.getMessage().contains("only allowed for cip-script-generator"));
    assertTrue(ex.getMessage().contains("exception and priority"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_ID), GraphPatch.class).isEmpty());
    assertEquals(
        CaptureFailureKind.VALIDATION,
        feedbackStore.lastPatchFailure(CONVERSATION_ID, CAPABILITY_ID).orElseThrow().kind());
  }

  @Test
  void rejectsOmittedPlaceholderScriptBodyFromScriptGenerator() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SCRIPT_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-placeholder",
            SCRIPT_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "script-1",
                    "script",
                    JsonNodeFactory.instance.textNode("<script body omitted, 379 chars>"))),
            List.of(),
            List.of(),
            "Placeholder script body");

    CaptureValidationException ex =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(ex.getMessage().contains("prompt redaction placeholder"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void rejectsIncompleteRbacPatchWhenRolesMissing() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SECURITY_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "HTTP Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-hello-rbac-no-roles",
            SECURITY_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "accessControlType",
                    JsonNodeFactory.instance.textNode("RBAC"))),
            List.of(),
            List.of(),
            "RBAC without roles");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("roles"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SECURITY_CAPABILITY_ID), GraphPatch.class).isEmpty());
    assertTrue(
        feedbackStore
            .lastPatchFailure(CONVERSATION_ID, SECURITY_CAPABILITY_ID)
            .filter(failure -> failure.kind() == CaptureFailureKind.VALIDATION)
            .isPresent());
  }

  @Test
  void rejectsCatalogIdentityPatchesFromServiceCallGenerator() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SERVICE_CALL_CAPABILITY_ID);
    ChainPlanGraph baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode(
                    "call-1", "service-call", "Call inventory", null, null, List.of())),
            List.of());
    planStore.put(CONVERSATION_ID, baseGraph);
    executionContextStore.set(
        new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
            "run-1",
            SERVICE_CALL_CAPABILITY_ID,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            baseGraph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of("service-call"),
                Set.of(),
                Map.of(
                    "service-call",
                    Set.of("propagateContext", "errorThrowing", "before", "after"))),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "http-service-call-catalog-binding",
            SERVICE_CALL_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "systemType",
                    JsonNodeFactory.instance.textNode("EXTERNAL")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationSystemId",
                    JsonNodeFactory.instance.textNode("sys-1")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationSpecificationGroupId",
                    JsonNodeFactory.instance.textNode("group-1")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationSpecificationId",
                    JsonNodeFactory.instance.textNode("spec-1")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationOperationId",
                    JsonNodeFactory.instance.textNode("op-1")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationOperationProtocolType",
                    JsonNodeFactory.instance.textNode("http")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationOperationMethod",
                    JsonNodeFactory.instance.textNode("GET")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "integrationOperationPath",
                    JsonNodeFactory.instance.textNode("/store/inventory")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "propagateContext",
                    JsonNodeFactory.instance.booleanNode(true)),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "errorThrowing",
                    JsonNodeFactory.instance.booleanNode(true))),
            List.of(),
            List.of(),
            "Bind HTTP catalog operation on call-1");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("ownership violation"), result);
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SERVICE_CALL_CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void repeatedValidationFailureTerminatesStream() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SERVICE_CALL_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode(
                    "call-1", "service-call", "Call inventory", null, null, List.of())),
            List.of()));
    GraphPatchCapture incomplete =
        new GraphPatchCapture(
            "partial",
            SERVICE_CALL_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "incomplete binding");

    // First failure: normal error, model should retry.
    String first = tool.captureGraphPatch(incomplete);
    assertTrue(first.contains("server binding is missing or stale"), first);

    CaptureValidationException failure =
        assertThrows(
            CaptureValidationException.class,
            () -> tool.captureGraphPatch(incomplete));

    assertTrue(failure.getMessage().contains("Repeated graph patch validation failure"));
    assertTrue(failure.getMessage().contains("server binding is missing or stale"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SERVICE_CALL_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void reportsMissingServerBindingWithoutRequestingCatalogPatches() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SERVICE_CALL_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode(
                    "call-1", "service-call", "Call inventory", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "service-call-runtime-options",
            SERVICE_CALL_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "call-1",
                    "propagateContext",
                    JsonNodeFactory.instance.booleanNode(true))),
            List.of(),
            List.of(),
            "Configure service-call runtime options");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("server binding is missing or stale"), result);
    assertFalse(result.contains("Submit propertyPatches"), result);
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SERVICE_CALL_CAPABILITY_ID), GraphPatch.class).isEmpty());
  }

  @Test
  void capturesCompleteRbacPatchWithRoles() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SECURITY_CAPABILITY_ID);
    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure-hello", "Secure Hello"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "HTTP Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of()));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "secure-hello-rbac",
            SECURITY_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "accessControlType",
                    JsonNodeFactory.instance.textNode("RBAC")),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "http-trigger-1",
                    "roles",
                    objectMapper.valueToTree(List.of("qip-viewer")))),
            List.of(),
            List.of(),
            "RBAC with roles");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SECURITY_CAPABILITY_ID), GraphPatch.class).isPresent());
  }

  @Test
  void defersCompletenessCheckWhenBaseGraphMissing() {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, SCRIPT_CAPABILITY_ID);

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-deferred",
            SCRIPT_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No base graph");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(captureSession.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID), GraphPatch.class).isPresent());
  }

  @Test
  void acceptsSlimNamingPayloadWithRawScriptStringProperties() throws Exception {
    String namingCapabilityId = "cip-naming-generator";
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, namingCapabilityId);

    planStore.put(
        CONVERSATION_ID,
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Greetings", "Greetings"),
            List.of(
                new ChainPlanNode(
                    "http-trigger",
                    "http-trigger",
                    "HTTP Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("contextPath", "/greetings"),
                        new PlanProperty("httpMethodRestrict", "GET"),
                        new PlanProperty("externalRoute", "false"))),
                new ChainPlanNode(
                    "script",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 'Hello world!';")))),
            List.of()));

    // Exact SLIM e2e failure shape: script.properties as a string array, not [{key,value}].
    String rawPayload =
        """
        {
          "patchId": "cip-naming-generator-valid-patch-labels-and-chain-name",
          "ownerCapabilityId": "cip-naming-generator",
          "nodePatches": [
            {
              "operation": "UPDATE",
              "targetNodeId": "http-trigger",
              "node": {
                "nodeId": "http-trigger",
                "type": "http-trigger",
                "label": "Receive Greetings Request",
                "parentNodeId": null,
                "order": null,
                "properties": [
                  {"key": "contextPath", "value": "/greetings"},
                  {"key": "httpMethodRestrict", "value": "GET"},
                  {"key": "externalRoute", "value": "false"}
                ]
              }
            },
            {
              "operation": "UPDATE",
              "targetNodeId": "script",
              "node": {
                "nodeId": "script",
                "type": "script",
                "label": "Return Hello World",
                "parentNodeId": null,
                "order": null,
                "properties": ["def x = 'Hello world!'\\nreturn x"]
              }
            }
          ],
          "edgePatches": [],
          "propertyPatches": [],
          "chainPatches": [
            {"operation": "UPDATE", "key": "name", "value": "Greetings.Internal.GetResponse"}
          ],
          "usedKnowledgeRefs": [],
          "rationale": "Apply corporate naming conventions to the chain name and element labels."
        }
        """;

    GraphPatchCapture patch = objectMapper.readValue(rawPayload, GraphPatchCapture.class);
    assertEquals("script", patch.nodePatches().get(1).node().properties().get(0).key());
    assertTrue(patch.nodePatches().get(1).node().properties().get(0).value().contains("Hello world!"));

    CaptureValidationException ex =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    // Naming cannot own the script body key — fail closed with CaptureValidationException.
    assertTrue(
        ex.getMessage().contains("only allowed for cip-script-generator"),
        () -> "expected script-ownership CaptureValidationException, got: " + ex.getMessage());
    assertTrue(feedbackStore.lastPatchFailure(CONVERSATION_ID, namingCapabilityId).isPresent());
    assertEquals(
        CaptureFailureKind.VALIDATION,
        feedbackStore.lastPatchFailure(CONVERSATION_ID, namingCapabilityId).orElseThrow().kind());
  }

  @Test
  void rewrittenMapper2SourceFailsCapture() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        TRANSFORMATION_CAPABILITY_ID,
        mapper2Site(),
        envelope,
        new GraphPatchOwnershipPolicy(
            false, false, Set.of("mapper-2"), Set.of(), Map.of("mapper-2", Set.of("mappingDescription"))));

    GraphPatchCapture patch =
        mappingDescriptionPatch(
            TRANSFORMATION_CAPABILITY_ID,
            identityCapture(envelope).withSource(tamperedSource(envelope)));

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Mapping parity:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, TRANSFORMATION_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void unknownMapper2TransformationFailsCapture() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        TRANSFORMATION_CAPABILITY_ID,
        mapper2Site(),
        envelope,
        new GraphPatchOwnershipPolicy(
            false, false, Set.of("mapper-2"), Set.of(), Map.of("mapper-2", Set.of("mappingDescription"))));

    GraphPatchCapture patch =
        mappingDescriptionPatch(
            TRANSFORMATION_CAPABILITY_ID,
            identityCapture(envelope)
                .withActions(List.of(identityAction(envelope).withTransformation("shout", List.of()))));

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Mapping contract:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, TRANSFORMATION_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void goodIdentityMapper2CapturePasses() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        TRANSFORMATION_CAPABILITY_ID,
        mapper2Site(),
        envelope,
        new GraphPatchOwnershipPolicy(
            false, false, Set.of("mapper-2"), Set.of(), Map.of("mapper-2", Set.of("mappingDescription"))));

    GraphPatchCapture patch =
        mappingDescriptionPatch(TRANSFORMATION_CAPABILITY_ID, identityCapture(envelope));

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, TRANSFORMATION_CAPABILITY_ID),
                GraphPatch.class)
            .isPresent());
  }

  @Test
  void twoMapper2IntentsValidateAgainstOwnEnvelopes() throws Exception {
    MappingEnvelope order = orderEnvelope().withMappingIntentId("map-order");
    MappingEnvelope customer = customerEnvelope().withMappingIntentId("map-customer");
    ChainPlanNode orderSite =
        new ChainPlanNode(
            "transform-map-order",
            MappingExecutionSite.ELEMENT_TYPE,
            "Map order",
            null,
            null,
            List.of(new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-order")));
    ChainPlanNode customerSite =
        new ChainPlanNode(
            "transform-map-customer",
            MappingExecutionSite.ELEMENT_TYPE,
            "Map customer",
            null,
            null,
            List.of(
                new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-customer")));
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Orders", "Orders"),
            List.of(orderSite, customerSite),
            List.of());
    planStore.put(CONVERSATION_ID, graph);
    CompilationArtifacts.Revision orderRev =
        compilationArtifacts.append(
            new CompilationArtifacts.AppendCommand(
                CONVERSATION_ID,
                CompilationArtifacts.Kind.MAPPING_ENVELOPE,
                "1",
                "test",
                "1",
                order,
                List.of(),
                null));
    CompilationArtifacts.Revision customerRev =
        compilationArtifacts.append(
            new CompilationArtifacts.AppendCommand(
                CONVERSATION_ID,
                CompilationArtifacts.Kind.MAPPING_ENVELOPE,
                "1",
                "test",
                "1",
                customer,
                List.of(),
                null));
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, TRANSFORMATION_CAPABILITY_ID);
    executionContextStore.set(
        new GraphPatchExecutionContext(
            "run-two-maps",
            TRANSFORMATION_CAPABILITY_ID,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            twoMapperBrief(),
            List.of(orderRev.reference(), customerRev.reference()),
            graph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of("mapper-2"),
                Set.of(),
                Map.of("mapper-2", Set.of("mappingDescription"))),
            ""));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "two-maps",
            TRANSFORMATION_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "transform-map-order",
                    MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
                    objectMapper.valueToTree(identityCapture(order))),
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "transform-map-customer",
                    MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
                    objectMapper.valueToTree(identityCapture(customer, "$.customerId")))),
            List.of(),
            List.of(),
            "Two mapper-2 mappingDescription captures");

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, TRANSFORMATION_CAPABILITY_ID),
                GraphPatch.class)
            .isPresent());
  }

  @Test
  void grabScriptOnMappingSiteFailsCapture() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        SCRIPT_CAPABILITY_ID,
        mappingScriptSite(),
        envelope,
        scriptMappingOwnership());

    GraphPatchCapture patch =
        scriptMappingPatch("@Grab('foo:bar:1')\ndef x = 1\n", List.of("$.orderId"));

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Groovy mapping:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void missingMappingCoverageFailsCapture() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        SCRIPT_CAPABILITY_ID,
        mappingScriptSite(),
        envelope,
        scriptMappingOwnership());

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "script-map-no-coverage",
            SCRIPT_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "transform-map-init",
                    "script",
                    JsonNodeFactory.instance.textNode("target['orderId'] = source['orderId']\n"))),
            List.of(),
            List.of(),
            "Script without mappingCoverage");

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("Mapping parity:"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void goodIdentityScriptMappingCapturePasses() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        SCRIPT_CAPABILITY_ID,
        mappingScriptSite(),
        envelope,
        scriptMappingOwnership());

    GraphPatchCapture patch =
        scriptMappingPatch("target['orderId'] = source['orderId']\n", List.of("$.orderId"));

    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(terminal.getMessage().contains("Graph patch captured"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID),
                GraphPatch.class)
            .isPresent());
  }

  @Test
  void scriptGeneratorMappingDescriptionFailsOwnership() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        SCRIPT_CAPABILITY_ID,
        mapper2Site(),
        envelope,
        scriptMappingOwnership());

    GraphPatchCapture patch =
        mappingDescriptionPatch(SCRIPT_CAPABILITY_ID, identityCapture(envelope));

    String result = tool.captureGraphPatch(patch);

    assertTrue(result.contains("ownership"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, SCRIPT_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  @Test
  void transformationGeneratorScriptKeyFailsClosed() throws Exception {
    MappingEnvelope envelope = orderEnvelope();
    bindMappingCapture(
        TRANSFORMATION_CAPABILITY_ID,
        mappingScriptSite(),
        envelope,
        new GraphPatchOwnershipPolicy(
            false, false, Set.of("mapper-2"), Set.of(), Map.of("mapper-2", Set.of("mappingDescription"))));

    GraphPatchCapture patch =
        new GraphPatchCapture(
            "transform-script",
            TRANSFORMATION_CAPABILITY_ID,
            List.of(),
            List.of(),
            List.of(
                new PropertyPatchCapture(
                    GraphPatchOperation.ADD,
                    "transform-map-init",
                    "script",
                    JsonNodeFactory.instance.textNode("def x = 1\n"))),
            List.of(),
            List.of(),
            "Wrong skill setting script");

    CaptureValidationException ex =
        assertThrows(CaptureValidationException.class, () -> tool.captureGraphPatch(patch));

    assertTrue(ex.getMessage().contains("only allowed for cip-script-generator"));
    assertTrue(
        captureSession
            .get(
                CaptureKey.capability(
                    CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, TRANSFORMATION_CAPABILITY_ID),
                GraphPatch.class)
            .isEmpty());
  }

  private void bindMappingCapture(
      String capabilityId,
      ChainPlanNode site,
      MappingEnvelope envelope,
      GraphPatchOwnershipPolicy ownership) {
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, capabilityId);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Orders", "Orders"),
            List.of(site),
            List.of());
    planStore.put(CONVERSATION_ID, graph);
    CompilationArtifacts.Revision revision =
        compilationArtifacts.append(
            new CompilationArtifacts.AppendCommand(
                CONVERSATION_ID,
                CompilationArtifacts.Kind.MAPPING_ENVELOPE,
                "1",
                "test",
                "1",
                envelope.withMappingIntentId(MappingExecutionSite.mappingIntentId(site)),
                List.of(),
                null));
    executionContextStore.set(
        new GraphPatchExecutionContext(
            "run-1",
            capabilityId,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            identityBrief(),
            List.of(revision.reference()),
            graph,
            ownership,
            ""));
  }

  private GraphPatchCapture mappingDescriptionPatch(
      String capabilityId, MappingDescriptionDocument captured) {
    return new GraphPatchCapture(
        "map-desc",
        capabilityId,
        List.of(),
        List.of(),
        List.of(
            new PropertyPatchCapture(
                GraphPatchOperation.ADD,
                "transform-map-init",
                MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
                objectMapper.valueToTree(captured))),
        List.of(),
        List.of(),
        "Mapper-2 mappingDescription");
  }

  private static GraphPatchCapture scriptMappingPatch(String script, List<String> coverage) {
    ArrayNode coverageNode = JsonNodeFactory.instance.arrayNode();
    for (String path : coverage) {
      coverageNode.add(path);
    }
    return new GraphPatchCapture(
        "script-map",
        SCRIPT_CAPABILITY_ID,
        List.of(),
        List.of(),
        List.of(
            new PropertyPatchCapture(
                GraphPatchOperation.ADD,
                "transform-map-init",
                "script",
                JsonNodeFactory.instance.textNode(script)),
            new PropertyPatchCapture(
                GraphPatchOperation.ADD,
                "transform-map-init",
                MappingExecutionSite.MAPPING_COVERAGE_PROPERTY,
                coverageNode)),
        List.of(),
        List.of(),
        "Script mapping with coverage");
  }

  private static GraphPatchOwnershipPolicy scriptMappingOwnership() {
    return new GraphPatchOwnershipPolicy(
        false,
        false,
        Set.of("script"),
        Set.of(),
        Map.of("script", Set.of("script", MappingExecutionSite.MAPPING_COVERAGE_PROPERTY)));
  }

  private static ChainPlanNode mapper2Site() {
    return new ChainPlanNode(
        "transform-map-init",
        MappingExecutionSite.ELEMENT_TYPE,
        "Map",
        null,
        null,
        List.of(new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init")));
  }

  private static ChainPlanNode mappingScriptSite() {
    return new ChainPlanNode(
        "transform-map-init",
        MappingExecutionSite.SCRIPT_ELEMENT_TYPE,
        "Map",
        null,
        null,
        List.of(new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init")));
  }

  private static RequirementBrief identityBrief() {
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
        .withMappingIntents(List.of(identityOrderId()));
  }

  private static RequirementBrief twoMapperBrief() {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map orderId and customerId",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-order",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "call-1",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED))),
                new MappingIntent(
                    "map-customer",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "call-2",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.customerId",
                            "$.customerId",
                            null,
                            MappingRuleStatus.USER_DEFINED)))));
  }

  private static MappingIntent identityOrderId() {
    return new MappingIntent(
        "map-init",
        "trigger-http",
        MappingPort.OUTPUT,
        "call-1",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)));
  }

  private MappingEnvelope orderEnvelope() throws Exception {
    JsonNode orderSchema =
        objectMapper.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
    return new JsonSchemaMessageSchemaFactory(objectMapper)
        .fromSides(
            mappingSide("trigger-http", MappingPort.OUTPUT, orderSchema),
            mappingSide("call-1", MappingPort.REQUEST, orderSchema));
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

  private static MappingDescriptionDocument identityCapture(MappingEnvelope envelope) {
    return identityCapture(envelope, "$.orderId");
  }

  private static MappingDescriptionDocument identityCapture(
      MappingEnvelope envelope, String jsonPath) {
    return new MappingDescriptionDocument(
        envelope.source(),
        envelope.target(),
        List.of(),
        List.of(identityAction(envelope, jsonPath)));
  }

  private static MappingAction identityAction(MappingEnvelope envelope) {
    return identityAction(envelope, "$.orderId");
  }

  private static MappingAction identityAction(MappingEnvelope envelope, String jsonPath) {
    String actionId = "action-" + jsonPath.replace("$.", "").replace('.', '-');
    return new MappingAction(
        actionId,
        List.of(attributeRef(envelope.idToPath(), jsonPath)),
        attributeRef(envelope.idToPath(), jsonPath),
        null);
  }

  private MappingEnvelope customerEnvelope() throws Exception {
    JsonNode customerSchema =
        objectMapper.readTree(
            """
            {
              "type": "object",
              "properties": { "customerId": { "type": "string" } },
              "required": ["customerId"]
            }
            """);
    return new JsonSchemaMessageSchemaFactory(objectMapper)
        .fromSides(
            mappingSide("trigger-http", MappingPort.OUTPUT, customerSchema),
            mappingSide("call-2", MappingPort.REQUEST, customerSchema));
  }

  private static AttributeReference attributeRef(Map<String, String> idToPath, String jsonPath) {
    List<String> pathIds = new ArrayList<>();
    for (Map.Entry<String, String> entry : idToPath.entrySet()) {
      if (jsonPath.equals(entry.getValue())) {
        pathIds.add(entry.getKey());
      }
    }
    if (pathIds.isEmpty()) {
      pathIds.add("missing-" + jsonPath.replace("$.", ""));
    }
    return new AttributeReference("body", pathIds);
  }

  private static MessageSchema tamperedSource(MappingEnvelope envelope) {
    MessageSchema source = envelope.source();
    ObjectType body = (ObjectType) source.body();
    ObjectSchema schema = body.schema();
    List<Attribute> attributes = new ArrayList<>(schema.attributes());
    attributes.add(new Attribute("tampered-id", "tampered", new StringType()));
    return new MessageSchema(
        source.headers(),
        source.properties(),
        new ObjectType(new ObjectSchema(schema.id(), attributes)));
  }
}
