package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.integration.catalog.materialize.*;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequiredLiteralNameBindingsTest {
  private static final String ENTRY_NAME = "Read the original request";
  private static final String WORK_NAME = "Compute the requested result";
  private static final String VALUE = "approved-output ";
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void nativeCaptureAndFinalGraphBindProtectedNamesWithoutRequiringNamesInTheScriptBody() throws Exception {
    var h = new Harness();
    var result = h.capture(input());
    assertTrue(result.accepted(), result.toString());
    var facts = RequirementCaptureProjection.facts(input());
    var named = RequestedElementValues.apply(graph(), facts);
    var brief = new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "").withFacts(facts);
    assertTrue(CompilerQualityValidator.validateApprovedValues(named, brief).isEmpty());
    var materializer = new ChainPlanPropertiesMaterializer(null, h.schemas, json);
    var body = ChainPlanPropertiesMaterializer.class.getDeclaredMethod("buildPatchBody", ChainPlanNode.class, MaterializationMap.class);
    body.setAccessible(true);
    for (var node : named.nodes()) {
      @SuppressWarnings("unchecked") var patch = (Map<String, Object>) body.invoke(materializer, node,
          new MaterializationMap(null, Map.of(), Map.of(), Map.of()));
      assertEquals(node.label(), patch.get("name"));
    }
    assertEquals(input(), h.store.get(h.chat).orElseThrow().authoredDraft());
    assertEquals(List.of(ENTRY_NAME), input().facts().getFirst().elementIntent().exactValues());
    assertEquals(List.of(WORK_NAME, VALUE), input().facts().get(1).elementIntent().exactValues());
  }

  @ParameterizedTest
  @ValueSource(strings = {"name", "null-name", "node-id", "missing-node", "type", "duplicate-node", "missing-owner", "foreign-owner",
      "duplicate-owner", "parent", "metadata-owner", "missing-runtime", "comment-runtime", "foreign-runtime"})
  void graphNameBindingDoesNotDischargeForeignSlotsOrNonNameRuntimeLiterals(String change) {
    var input = input();
    var facts = RequirementCaptureProjection.facts(input);
    var named = RequestedElementValues.apply(graph(), facts);
    var tree = json.<ObjectNode>valueToTree(named);
    var script = (ObjectNode) tree.at("/nodes/1");
    switch (change) {
      case "name" -> script.put("label", "Changed name");
      case "null-name" -> script.putNull("label");
      case "node-id" -> script.put("nodeId", "foreign");
      case "missing-node" -> tree.withArray("nodes").remove(1);
      case "type" -> script.put("type", "set-property");
      case "duplicate-node" -> tree.withArray("nodes").add(script.deepCopy());
      case "missing-owner" -> tree.withArray("nodes").remove(0);
      case "foreign-owner" -> ((ObjectNode) tree.at("/nodes/0")).put("nodeId", "foreign");
      case "duplicate-owner" -> tree.withArray("nodes").add(tree.at("/nodes/0").deepCopy());
      case "parent" -> script.put("parentNodeId", "foreign");
      case "metadata-owner" -> script.withArray("properties").addObject().put("key", "serviceCallId").put("value", "foreign");
      case "missing-runtime", "comment-runtime", "foreign-runtime" -> {
        ((ObjectNode) script.at("/properties/0")).put("value", change.equals("comment-runtime")
            ? "// approved-output \nreturn 'other'" : "return 'other'");
        if (change.equals("foreign-runtime")) {
          var other = script.deepCopy(); other.put("nodeId", "foreign");
          ((ObjectNode) other.at("/properties/0")).put("value", "return 'approved-output '");
          tree.withArray("nodes").add(other);
        }
      }
      default -> throw new AssertionError(change);
    }
    var candidate = json.convertValue(tree, ChainPlanGraph.class);
    var findings = CompilerQualityValidator.validateApprovedValues(candidate,
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "").withFacts(facts));
    assertFalse(findings.isEmpty(), change);
    assertFalse(RequiredLiteralBindings.missingScriptLiterals(facts.get(1), candidate).isEmpty(), change);
  }

  @ParameterizedTest
  @ValueSource(strings = {"owner", "anchor-id", "local-id", "type", "hook-before", "hook-after", "chain", "blank-name", "source-name"})
  void captureStillValidatesNativeOwnerPlacementTypeAndSourceName(String change) throws Exception {
    var h = new Harness();
    var tree = json.<ObjectNode>valueToTree(input());
    var anchor = (ObjectNode) tree.at("/facts/0/elementIntent");
    var script = (ObjectNode) tree.at("/facts/1/elementIntent");
    switch (change) {
      case "owner" -> script.put("ownerInteractionId", "foreign");
      case "anchor-id" -> anchor.put("elementRef", "foreign");
      case "local-id" -> script.put("elementRef", "entry");
      case "type" -> script.put("elementType", "unknown-native-type");
      case "hook-before" -> script.put("placement", "BEFORE_HOOK");
      case "hook-after" -> script.put("placement", "AFTER_HOOK");
      case "chain" -> script.put("placement", "CHAIN");
      case "blank-name" -> script.put("requestedName", " ");
      case "source-name" -> script.put("requestedName", "Foreign author name");
      default -> throw new AssertionError(change);
    }
    assertFalse(h.capture(json.convertValue(tree, DraftInput.class)).accepted(), change);
    assertTrue(h.store.get(h.chat).isEmpty());
  }

  @Test
  void runtimeConstantsStillNeedExactAstValuesWhenTheNameSlotIsValid() {
    var facts = RequirementCaptureProjection.facts(input());
    var named = RequestedElementValues.apply(graph(), facts);
    assertEquals(List.of(), RequiredLiteralBindings.unbound(facts.getFirst(), facts));
    assertEquals(List.of(), RequiredLiteralBindings.missingScriptLiterals(facts.get(1), named));
    var wrong = named.withNodeProperty("work", "script", "return 'approved-output'");
    assertEquals(List.of(VALUE), RequiredLiteralBindings.missingScriptLiterals(facts.get(1), wrong));
  }

  @Test
  void aDeclaredParentAndMatchingOwnerMetadataRetainTheNativeNameSlot() {
    var facts = RequirementCaptureProjection.facts(input());
    var original = facts.get(1);
    var intent = original.elementIntent();
    var declared = new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(), intent.ownerInteractionId(),
        intent.successorElementRef(), intent.predecessorElementRef(), "container", intent.requestedName(),
        intent.requestedProperties(), intent.exactValues());
    var fact = new RequirementFact(original.sourceFactId(), original.polarity(), original.kind(), original.capabilityKey(), original.text(),
        original.participant(), original.operation(), original.topic(), original.httpMethod(), original.path(), original.serviceCallId(),
        original.interactionIds(), original.policyConflict(), declared);
    var named = RequestedElementValues.apply(graph(), facts);
    var nodes = new java.util.ArrayList<>(named.nodes());
    var script = nodes.get(1);
    nodes.set(1, new ChainPlanNode(script.nodeId(), script.type(), script.label(), "container", null,
        List.of(new PlanProperty("script", "return 'approved-output '"), new PlanProperty("serviceCallId", "entry"))));
    nodes.add(new ChainPlanNode("container", "condition", "Container", null, null, List.of()));
    assertTrue(RequiredLiteralBindings.missingScriptLiterals(fact,
        new ChainPlanGraph(named.schemaVersion(), named.chain(), nodes, named.edges())).isEmpty());
  }

  @Test
  void nameBindingCannotComeFromAnotherFactsNameOrAnotherNodesLabel() {
    var facts = RequirementCaptureProjection.facts(input());
    var name = facts.getFirst().elementIntent();
    var changed = new RequirementElementIntent(name.elementRef(), name.elementType(), name.placement(), name.ownerInteractionId(),
        name.successorElementRef(), name.predecessorElementRef(), name.requestedName(), name.requestedProperties(), List.of(WORK_NAME));
    var original = facts.getFirst();
    var fact = new RequirementFact(original.sourceFactId(), original.polarity(), original.kind(), original.capabilityKey(), original.text(),
        original.participant(), original.operation(), original.topic(), original.httpMethod(), original.path(), original.serviceCallId(),
        original.interactionIds(), original.policyConflict(), changed);
    assertEquals(List.of(WORK_NAME), RequiredLiteralBindings.unbound(fact, facts));
  }

  private static DraftInput input() {
    var anchor = new RequirementElementIntent("entry", "http-trigger", Placement.ANCHOR, "entry", null, null,
        ENTRY_NAME, List.of(), List.of(ENTRY_NAME));
    var script = new RequirementElementIntent("work", "script", Placement.LOCAL_NODE, "entry", null, "entry",
        WORK_NAME, List.of(), List.of(WORK_NAME, VALUE));
    return withNativeAnchors(new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /names", null, null, null)), List.of()), List.of(
        new FactInput("entry-name", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE, "Name the trigger " + ENTRY_NAME, null, null, anchor),
        new FactInput("work-name", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Name the script " + WORK_NAME + " and emit " + VALUE, null, null, script)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/names", null, null, null)),
        List.of(), new DraftSettings(false, null)));
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph("1.0", new ChainSection("Names", ""), List.of(
        new ChainPlanNode("entry", "http-trigger", "entry", null, null, List.of()),
        new ChainPlanNode("work", "script", "work", null, null, List.of(new PlanProperty("script", "return 'approved-output '")))),
        List.of(new ChainPlanEdge("run", "entry", "work", null)));
  }

  private final class Harness {
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final RequirementDraftStore store = new RequirementDraftStore(artifacts, sessions, conversations);
    final RequirementCaptureTools tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json);
    final DeterministicElementSchemaService schemas = DeterministicElementSchemaService.createForUnitTests(json);
    final String chat = UUID.randomUUID().toString();
    Harness() throws Exception {
      for (var entry : Map.of("conversations", conversations, "elementSchemas", schemas).entrySet()) {
        var field = RequirementCaptureTools.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(tools, entry.getValue());
      }
      String text = "Receive POST /names with a trigger named " + ENTRY_NAME + ". Add a local script named " + WORK_NAME
          + " that emits " + VALUE + ". Proceed without IDS.";
      var source = conversations.archive().beginInput(chat, text, null);
      conversations.archive().resolveInput(chat, source, text); store.beginTurn(chat);
    }
    RequirementCaptureResult capture(DraftInput input) throws Exception {
      try (var ignored = ToolSession.open(chat)) {
        return json.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      }
    }
  }
}
