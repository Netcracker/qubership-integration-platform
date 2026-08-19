package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditDisposition;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditStructureBase;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ChainStructureCaptureToolTest {

  private static final String CONVERSATION_ID = "conv-structure";

  private CaptureSession session;
  private DeterministicElementSchemaService schemaService;
  private ChainStructurePropertySanitizer propertySanitizer;
  private ChainStructureCaptureTool tool;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    schemaService = mock(DeterministicElementSchemaService.class);
    when(schemaService.hasElementSchema(anyString())).thenReturn(false);
    when(schemaService.allowedPatchPropertyKeys(anyString())).thenReturn(Set.of());
    propertySanitizer = new ChainStructurePropertySanitizer(schemaService);
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    tool =
        new ChainStructureCaptureTool(
            session,
            new ChainPlanGraphValidator(schemaService),
            new ObjectMapper(),
            feedbackStore,
            new CaptureToolOutcomeGateway(
                feedbackStore.fingerprintStore(), feedbackStore),
            propertySanitizer);
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
  }

  @Test
  void capturesValidStructure() {
    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureChainStructure(validStructure()));

    assertTrue(terminal.getMessage().contains("captured"));
    assertEquals(
        validGraph(),
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, CONVERSATION_ID),
                ChainStructure.class)
            .orElseThrow()
            .graph());
  }

  @Test
  void rejectsStructureWithCycleWithoutReplacingPriorValue() {
    assertThrows(CaptureValidationException.class, () -> tool.captureChainStructure(validStructure()));

    String rejected = tool.captureChainStructure(cyclicStructure());
    assertTrue(rejected.contains("Structure validation failed"));
    assertEquals(
        validGraph(),
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, CONVERSATION_ID),
                ChainStructure.class)
            .orElseThrow()
            .graph());
  }

  @Test
  void rejectsStructureWithoutGraph() {
    String result = tool.captureChainStructure(new ChainStructure(null, List.of(), List.of()));

    assertTrue(result.contains("graph"));
  }

  @Test
  void mergesConfiguredTriggerPropertiesWhenStructureOmitsThem() {
    session.accept(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
        new ConfiguredTriggerSet(
            1,
            List.of(
                new ConfiguredTrigger(
                    "http-entry",
                    "http-trigger-1",
                    "http-trigger",
                    "GET /safe-echo",
                    List.of(
                        new PlanProperty("contextPath", "/safe-echo"),
                        new PlanProperty("httpMethodRestrict", "GET"),
                        new PlanProperty("externalRoute", "false")))),
            List.of(),
            List.of()),
        "ok",
        "dup");

    ChainStructure withoutTriggerProps =
        new ChainStructure(new ChainPlanGraph(
                "1.0",
                new ChainSection("SafeEcho", "SafeEcho"),
                List.of(
                    new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, null),
                    new ChainPlanNode("script-1", "script", "Script", null, 2, List.of())),
                List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null))),
            List.of(),
            List.of());

    assertThrows(
        CaptureValidationException.class, () -> tool.captureChainStructure(withoutTriggerProps));

    ChainPlanGraph stored =
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, CONVERSATION_ID),
                ChainStructure.class)
            .orElseThrow()
            .graph();
    ChainPlanNode trigger =
        stored.nodes().stream()
            .filter(node -> "http-trigger-1".equals(node.nodeId()))
            .findFirst()
            .orElseThrow();
    assertEquals("/safe-echo", propertyValue(trigger, "contextPath"));
    assertEquals("GET", propertyValue(trigger, "httpMethodRestrict"));
    assertEquals("false", propertyValue(trigger, "externalRoute"));
  }

  @Test
  void capturesStructureAfterStrippingUnknownScriptProperty() {
    when(schemaService.hasElementSchema("script")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    ChainStructure capture =
        structureWithScriptProperties(
            List.of(
                new PlanProperty("language", "Groovy"),
                new PlanProperty("script", "return 'hello'")));

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () -> tool.captureChainStructure(capture));

    assertTrue(terminal.getMessage().contains("captured"));
    ChainStructure accepted =
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, CONVERSATION_ID),
                ChainStructure.class)
            .orElseThrow();
    ChainPlanNode script =
        accepted.graph().nodes().stream()
            .filter(node -> "script-1".equals(node.nodeId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(new PlanProperty("script", "return 'hello'")),
        script.properties());
    assertEquals(capture.graph().edges(), accepted.graph().edges());
  }

  private static ChainStructure structureWithScriptProperties(
      List<PlanProperty> properties) {
    return new ChainStructure(new ChainPlanGraph(
            "1.0",
            new ChainSection("Greeting", "Greeting"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1",
                    "http-trigger",
                    "HTTP trigger",
                    null,
                    1,
                    List.of()),
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Greeting script",
                    null,
                    2,
                    properties)),
            List.of(
                new ChainPlanEdge(
                    "edge-http-script",
                    "http-trigger-1",
                    "script-1",
                    null))),
        List.of("fact-1"),
        List.of());
  }

  private static String propertyValue(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }

  @Test
  void captureSessionRejectsWrongRuntimeTypeForStructureSlot() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                session.accept(
                    CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, CONVERSATION_ID),
                    "not-structure",
                    "ok",
                    "dup"));

    assertTrue(thrown.getMessage().contains("does not match slot"));
  }

  @Test
  void acceptsAWrapWhoseTriggerEdgeOnlyTheMergeRestores() {
    session.set(
        CaptureKey.conversation(CaptureSlot.CHAIN_EDIT_STRUCTURE_BASE, CONVERSATION_ID),
        new ChainEditStructureBase(validGraph(), wrapIntent()));

    CaptureValidationException accepted =
        assertThrows(
            CaptureValidationException.class,
            () -> tool.captureChainStructure(new ChainStructure(wrapCapture(), List.of(), List.of())));

    assertTrue(accepted.getMessage().contains("Chain structure captured"));
  }

  @Test
  void reportsAMergeTheCompilerWouldRefuseAsRepairableFeedback() {
    session.set(
        CaptureKey.conversation(CaptureSlot.CHAIN_EDIT_STRUCTURE_BASE, CONVERSATION_ID),
        new ChainEditStructureBase(validGraph(), wrapIntent()));
    ChainPlanGraph reparentsTheTrigger =
        new ChainPlanGraph(
            "1.0",
            validGraph().chain(),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", "try-1", 1, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "try-1", 2, List.of()),
                new ChainPlanNode("wrap-1", "try-catch-finally-2", "Wrap", null, null, List.of()),
                new ChainPlanNode("try-1", "try-2", "Try", "wrap-1", null, List.of())),
            List.of());

    String result =
        tool.captureChainStructure(new ChainStructure(reparentsTheTrigger, List.of(), List.of()));

    assertTrue(result.contains("reparented non-target node"), result);
  }

  @Test
  void acceptsAReplacementThatOmitsTheReplacedElement() {
    session.set(
        CaptureKey.conversation(CaptureSlot.CHAIN_EDIT_STRUCTURE_BASE, CONVERSATION_ID),
        new ChainEditStructureBase(validGraph(), replaceIntent()));

    CaptureValidationException accepted =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.captureChainStructure(
                    new ChainStructure(replacementCapture(), List.of(), List.of())));

    assertTrue(accepted.getMessage().contains("Chain structure captured"));
  }

  /** A wrap whose capture lists no edges at all; the merge has to bring the trigger's over. */
  private static ChainPlanGraph wrapCapture() {
    return new ChainPlanGraph(
        "1.0",
        validGraph().chain(),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("script-1", "script", "Script", "try-1", 2, List.of()),
            new ChainPlanNode("wrap-1", "try-catch-finally-2", "Wrap", null, null, List.of()),
            new ChainPlanNode("try-1", "try-2", "Try", "wrap-1", null, List.of())),
        List.of());
  }

  private static ChainPlanGraph replacementCapture() {
    return new ChainPlanGraph(
        "1.0",
        validGraph().chain(),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("map-1", "script", "Map", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("map-to-call", "map-1", "call-1", null)));
  }

  private static ChainEditIntent replaceIntent() {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of("script-1"),
        "replace the script with a mapper and a service call",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.REMOVE);
  }

  private static ChainEditIntent wrapIntent() {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of("script-1"),
        "wrap the script with error handling",
        null,
        "try-catch-finally-2",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.NEST);
  }

  private static ChainStructure validStructure() {
    return new ChainStructure(validGraph(), List.of("fact-1"), List.of());
  }

  private static ChainStructure cyclicStructure() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Cycle", "Cycle"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
                new ChainPlanNode("script-1", "script", "Script A", "script-2", 2, List.of()),
                new ChainPlanNode("script-2", "script", "Script B", "script-1", 3, List.of())),
            List.of(
                new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null),
                new ChainPlanEdge("edge-2", "script-1", "script-2", null)));
    return new ChainStructure(graph, List.of("fact-2"), List.of());
  }

  private static ChainPlanGraph validGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greeting", "Greeting"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("script-1", "script", "Script", null, 2, List.of())),
        List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
  }
}
