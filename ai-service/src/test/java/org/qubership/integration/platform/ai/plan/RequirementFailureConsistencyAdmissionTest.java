package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

class RequirementFailureConsistencyAdmissionTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private static final String SOURCE = "Receive POST /work. Name the service call Remote request and the local script Render result. "
      + "Use errorThrowing=false. Return every HTTP response to that script and deliver success or failure fields. No IDS.";

  @ParameterizedTest
  @ValueSource(strings = {"false", "FALSE", " false "})
  void nativeBooleanFalseEncodingsStayLiteralAndHaveTheSameConsistencyMeaning(String value) {
    var tree = (ObjectNode) json.valueToTree(input());
    ((ObjectNode) tree.at("/facts/1/elementIntent/requestedProperties/0")).put("value", value);
    var input = json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
    var schemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(json);
    assertEquals(Boolean.FALSE, schemas.coercePatchPropertyValue("service-call", "errorThrowing", value));
    assertEquals(1, RequirementFailureConsistency.issues(input, bindings()).size());
    assertEquals(value, input.facts().get(1).elementIntent().requestedProperties().getFirst().value());
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-boolean", "false-extra", "\u2003false\u2003"})
  void unknownBooleanEncodingsCannotCreateNativeCorrectionAuthority(String value) {
    var tree = (ObjectNode) json.valueToTree(input());
    ((ObjectNode) tree.at("/facts/1/elementIntent/requestedProperties/0")).put("value", value);
    var schemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(json);
    assertFalse(Boolean.FALSE.equals(schemas.coercePatchPropertyValue("service-call", "errorThrowing", value)));
    assertTrue(RequirementFailureConsistency.issues(json.convertValue(tree, RequirementCaptureInput.DraftInput.class), bindings()).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"inline", "scope", "terminal", "foreign", "negative", "duplicate", "missing", "unbound", "kafka", "duplicate-binding"})
  void ambiguousOrDifferentEvidenceDoesNotAuthorizeAPropagationRepair(String variant) {
    var tree = (ObjectNode) json.valueToTree(input());
    var bindings = bindings();
    switch (variant) {
      case "inline" -> ((ObjectNode) tree.at("/flow/interactions/1")).put("failureMode", "INLINE_RESPONSE");
      case "scope" -> ((ObjectNode) tree.at("/flow/interactions/1")).put("failureMode", "ERROR_SCOPE");
      case "terminal" -> {
        ((ObjectNode) tree.at("/facts/1/elementIntent")).putNull("successorElementRef");
        ((ObjectNode) tree.at("/facts/2/elementIntent")).putNull("predecessorElementRef");
      }
      case "foreign" -> ((ObjectNode) tree.at("/facts/2/elementIntent")).put("ownerInteractionId", "entry");
      case "negative" -> ((ObjectNode) tree.at("/facts/1")).put("polarity", "NEGATIVE");
      case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) tree.at("/facts/1/elementIntent/requestedProperties"))
          .add(tree.at("/facts/1/elementIntent/requestedProperties/0").deepCopy());
      case "missing" -> ((ObjectNode) tree.at("/facts/1/elementIntent")).putArray("requestedProperties");
      case "unbound" -> bindings = List.of();
      case "kafka" -> bindings = List.of(protocol(RequirementFailureConsistencyTest.binding("remote"), "kafka"));
      case "duplicate-binding" -> bindings = List.of(bindings.getFirst(), bindings.getFirst());
      default -> throw new AssertionError(variant);
    }
    assertTrue(RequirementFailureConsistency.issues(json.convertValue(tree, RequirementCaptureInput.DraftInput.class), bindings).isEmpty());
  }

  @Test
  void explicitDeliveryOwnerAndBothInteractionReferencesProveTheSavedOmResponseRelation() {
    var tree = (ObjectNode) json.valueToTree(input());
    ((ObjectNode) tree.at("/facts/2/elementIntent")).put("ownerInteractionId", "delivery");
    ((com.fasterxml.jackson.databind.node.ArrayNode) tree.at("/facts/2/interactionIds")).add("delivery");
    var accepted = json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
    assertEquals(1, RequirementFailureConsistency.issues(accepted, bindings()).size());
    ((com.fasterxml.jackson.databind.node.ArrayNode) tree.at("/facts/2/interactionIds")).remove(0);
    assertTrue(RequirementFailureConsistency.issues(json.convertValue(tree, RequirementCaptureInput.DraftInput.class), bindings()).isEmpty());
  }

  @Test
  void namedModeRepairRestoresADroppedProtectedFactAndContinues() throws Exception {
    var h = new Harness();
    h.begin();
    try {
      var refusal = h.call("captureRequirementDraft", Map.of("draft", input()));
      assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
      String token = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token").asText();
      var modeUpdate = update(mode(ServiceCallFailureMode.INLINE_RESPONSE));
      var changes = new RequirementCaptureInput.DraftUpdate(modeUpdate.addInteractions(), modeUpdate.updateInteractions(),
          modeUpdate.removeInteractionIds(), modeUpdate.addTransitions(), modeUpdate.removeTransitions(),
          modeUpdate.addFacts(), modeUpdate.updateFacts(), List.of("call-options"), modeUpdate.setCapabilities(),
          modeUpdate.removeCapabilityInteractionIds(), modeUpdate.addQuestions(), modeUpdate.updateQuestions(),
          modeUpdate.removeQuestionIds(), modeUpdate.settings(), modeUpdate.amendments(), token);
      var result = h.call("updateRequirementDraft", Map.of("changes", changes));
      assertTrue(result.path("accepted").asBoolean(), result.toString());
      var saved = h.store.get(h.chat).orElseThrow().authoredDraft();
      assertEquals(ServiceCallFailureMode.INLINE_RESPONSE, saved.flow().interactions().get(1).failureMode());
      assertTrue(saved.facts().stream().noneMatch(fact -> "call-options".equals(fact.sourceFactId())),
          saved.facts().toString());
      assertTrue(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE")).path("accepted").asBoolean());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @Test
  void rejectedErrorScopeNamesTheCandidateMode() {
    var errorScope = withMode(input(), ServiceCallFailureMode.ERROR_SCOPE);
    assertTrue(RequirementFailureConsistency.issues(errorScope, bindings()).isEmpty());
    var refused = RequirementFailureConsistency.continuedResponsesRequireInlineResponse(errorScope, bindings());
    assertEquals(1, refused.size(), refused.toString());
    assertModeFinding(refused.getFirst().message(), "ERROR_SCOPE");

    var propagate = RequirementFailureConsistency.continuedResponsesRequireInlineResponse(input(), bindings());
    assertEquals(1, propagate.size(), propagate.toString());
    assertModeFinding(propagate.getFirst().message(), "PROPAGATE");

    assertTrue(RequirementFailureConsistency.continuedResponsesRequireInlineResponse(
        withMode(input(), ServiceCallFailureMode.INLINE_RESPONSE), bindings()).isEmpty());
  }

  @Test
  void rejectedCaptureOmitsAcceptedIntentFreezeWhenNoAuthoredDraftExists() throws Exception {
    var h = new Harness();
    h.begin();
    try {
      var draft = RequirementCaptureProjection.toDraft(
          withMode(input(), ServiceCallFailureMode.INLINE_RESPONSE), null, bindings(), "1", "hash");
      var legacyTree = (ObjectNode) json.valueToTree(draft);
      legacyTree.putNull("authoredDraft");
      h.store.put(h.chat, json.convertValue(legacyTree, RequirementDraft.class));
      assertTrue(h.store.get(h.chat).isPresent());
      assertNull(h.store.get(h.chat).orElseThrow().authoredDraft());

      var refusal = h.call("captureRequirementDraft", Map.of("draft", input()));
      assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
      refusal.path("issues").forEach(issue -> assertFalse(
          issue.path("message").asText().contains("keep accepted elementIntent unchanged"),
          refusal.toString()));
    } finally {
      ProductCapabilityCaptureContext.unbind();
    }
  }

  @Test
  void propagateConflictRequiresInlineResponseWithoutPreservingFacts() {
    var findings = RequirementFailureConsistency.issues(input(), bindings());
    assertEquals(1, findings.size(), findings.toString());
    var finding = findings.getFirst();
    assertEquals("FAILURE_MODE_CONFLICT", finding.code());
    String message = finding.message();
    assertTrue(message.contains("errorThrowing=false"), message);
    assertTrue(message.contains("INLINE_RESPONSE"), message);
    assertFalse(message.toLowerCase().contains("preserve"), message);
    assertFalse(message.contains("all facts"), message);
    assertFalse(message.contains("names, values"), message);
    assertFalse(message.contains("and order"), message);
  }

  @Test
  void rejectedErrorScopeAdmissionNamesTheCandidateMode() throws Exception {
    var h = new Harness();
    h.begin();
    try {
      var malformed = (ObjectNode) json.valueToTree(input());
      ((ObjectNode) malformed.at("/facts/2/elementIntent")).putNull("ownerInteractionId");
      var first = h.call("captureRequirementDraft", Map.of("draft", malformed));
      assertFalse(first.path("accepted").asBoolean(), first.toString());
      assertNull(h.store.repairConstraints(h.chat));

      var refused = h.call("captureRequirementDraft",
          Map.of("draft", withMode(input(), ServiceCallFailureMode.ERROR_SCOPE)));
      assertFalse(refused.path("accepted").asBoolean(), refused.toString());
      String message = failureModeMessage(refused);
      assertModeFinding(message, "ERROR_SCOPE");
      assertTrue(h.store.get(h.chat).isEmpty());

      var corrected = withMode(input(), ServiceCallFailureMode.INLINE_RESPONSE);
      var accepted = h.call("captureRequirementDraft", Map.of("draft", corrected));
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      assertEquals(corrected, h.store.get(h.chat).orElseThrow().authoredDraft());
    } finally {
      ProductCapabilityCaptureContext.unbind();
    }
  }

  private static void assertModeFinding(String message, String mode) {
    assertTrue(message.contains("The failure mode is " + mode), message);
    assertTrue(message.contains("INLINE_RESPONSE"), message);
    assertFalse(message.contains("all facts"), message);
    assertFalse(message.contains("names, values"), message);
    assertFalse(message.contains("and order"), message);
    assertFalse(message.toLowerCase().contains("preserve"), message);
    if (!"PROPAGATE".equals(mode)) {
      assertFalse(message.contains("PROPAGATE"), message);
      assertFalse(message.contains("stop before successors"), message);
    }
  }

  private static String failureModeMessage(JsonNode result) {
    var messages = new ArrayList<String>();
    result.path("issues").forEach(issue -> {
      if ("FAILURE_MODE_CONFLICT".equals(issue.path("code").asText())) {
        messages.add(issue.path("message").asText());
      }
    });
    assertEquals(1, messages.size(), result.toString());
    return messages.getFirst();
  }

  @Test
  void rejectedInitializationRetainsRawModeAndOnlyTheCorrectedModeIsAdmitted() throws Exception {
    var h = new Harness();
    h.begin();
    try {
      var refusal = h.call("captureRequirementDraft", Map.of("draft", input()));
      assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
      assertEquals("/draft/flow/interactions/remote/failureMode", refusal.at("/issues/0/path").asText());
      var expected = RequirementFailureConsistency.issues(input(), bindings());
      assertEquals(expected.size(), refusal.path("issues").size(), refusal.toString());
      for (int i = 0; i < expected.size(); i++) {
        String message = refusal.at("/issues/" + i + "/message").asText();
        assertEquals(expected.get(i).message(), message);
        assertFalse(message.contains("keep accepted elementIntent unchanged"), message);
      }
      var attempt = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      assertEquals("PROPAGATE", attempt.payload().at("/arguments/flow/interactions/1/failureMode").asText());
      assertEquals(refusal.path("issues"), attempt.payload().path("issues"));
      assertTrue(h.store.get(h.chat).isEmpty());
      var corrected = RequirementFailureConsistencyTest.input(ServiceCallFailureMode.INLINE_RESPONSE);
      corrected = withNativeAnchors(corrected);
      assertTrue(h.call("captureRequirementDraft", Map.of("draft", corrected)).path("accepted").asBoolean());
      assertEquals(input().facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
      assertTrue(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE")).path("accepted").asBoolean());
      assertEquals(attempt, h.artifacts.get(attempt.compilationId(), attempt.reference()).orElseThrow());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aLateHttpBindingAllowsOnlyTheCurrentStoredModeRepairAcrossReload(boolean reload) throws Exception {
    var h = new Harness(); h.available.set(List.of()); h.begin();
    assertTrue(h.call("captureRequirementDraft", Map.of("draft", input())).path("accepted").asBoolean());
    h.available.set(bindings());
    if (reload) h.reload();
    h.begin();
    try {
      assertFalse(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE")).path("accepted").asBoolean());
      assertEquals(Set.of("/interactions/remote/failureMode"), h.store.activeRequirementRepairPaths(h.chat));
      var base = h.store.get(h.chat).orElseThrow().authoredDraft();
      assertTrue(h.call("updateRequirementDraft", Map.of("changes", update(mode(ServiceCallFailureMode.INLINE_RESPONSE))))
          .path("accepted").asBoolean());
      assertEquals(base.facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
      assertEquals(base.flow().transitions(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
      assertTrue(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE")).path("accepted").asBoolean());
      assertTrue(h.store.activeRequirementRepairPaths(h.chat).isEmpty());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"error-scope", "null", "flag", "text", "neighbor", "binding", "no-context", "stale-source", "stale-base",
      "context-error-scope", "context-null", "context-neighbor"})
  void theNativeModeAllowanceCannotAuthorizeCollateralOrStaleChanges(String variant) throws Exception {
    var h = new Harness(); h.begin();
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(input(), null, bindings(), "1", "hash"));
    if (variant.startsWith("context-")) {
      var nativeFinding = new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
          "FAILURE_MODE_CONFLICT", "HTTP error responses must reach the normal response step.", true,
          org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
          "remote", "/draft/flow/interactions/remote/failureMode");
      String repairPath = RequirementDraftStore.requirementRepairPath(h.store.get(h.chat).orElseThrow(), nativeFinding);
      assertEquals("/interactions/remote/failureMode", repairPath);
      ProductCapabilityCaptureContext.bindDiscovery("run-" + h.chat, h.chat, h.store.get(h.chat).orElseThrow(), Map.of(),
          Set.of(repairPath, "/interactions/delivery/description"), ignored -> {});
      assertTrue(h.store.activeRequirementRepairPaths(h.chat).contains("/interactions/delivery/description"));
    }
    var base = h.store.latestRevision(h.chat).orElseThrow().reference();
    var tree = (ObjectNode) json.valueToTree(update(mode(ServiceCallFailureMode.INLINE_RESPONSE)));
    switch (variant.startsWith("context-") ? variant.substring("context-".length()) : variant) {
      case "error-scope" -> ((ObjectNode) tree.at("/updateInteractions/0")).put("failureMode", "ERROR_SCOPE");
      case "null" -> ((ObjectNode) tree.at("/updateInteractions/0")).putNull("failureMode");
      case "flag", "text" -> {
        var fact = (ObjectNode) json.valueToTree(input().facts().get(variant.equals("flag") ? 1 : 2));
        if (variant.equals("flag")) ((ObjectNode) fact.at("/elementIntent/requestedProperties/0")).put("value", "true");
        else fact.put("text", "Replace the requested behavior.");
        ((com.fasterxml.jackson.databind.node.ArrayNode) tree.path("updateFacts")).add(fact);
      }
      case "neighbor" -> {
        var extra = (ObjectNode) json.valueToTree(input().flow().interactions().get(2));
        extra.put("failureMode", "INLINE_RESPONSE");
        ((com.fasterxml.jackson.databind.node.ArrayNode) tree.path("updateInteractions")).add(extra);
      }
      case "binding" -> h.available.set(List.of(protocol(bindings().getFirst(), "kafka"), bindings().get(1)));
      case "no-context" -> ProductCapabilityCaptureContext.unbind();
      case "stale-source" -> {
        var source = h.archive.beginInput(h.chat, "An unrelated new author source.", null);
        h.archive.resolveInput(h.chat, source, "An unrelated new author source.");
      }
      case "stale-base" -> h.store.put(h.chat, h.store.get(h.chat).orElseThrow().withAssembledText("A derived revision."));
      default -> throw new AssertionError(variant);
    }
    try {
      if (variant.equals("stale-base")) {
        var proposed = RequirementCaptureProjection.toDraft(withNativeAnchors(RequirementFailureConsistencyTest.input(ServiceCallFailureMode.INLINE_RESPONSE)),
            null, bindings(), "1", "hash");
        assertTrue(h.store.admitCapture(h.chat, base, proposed, null, Map.of()).stream()
            .anyMatch(issue -> "STALE_REQUIREMENT_BASE".equals(issue.code())));
      } else {
        var result = h.call("updateRequirementDraft", Map.of("changes", tree));
        assertFalse(result.path("accepted").asBoolean(), variant + ": " + result);
        assertEquals(input(), h.store.get(h.chat).orElseThrow().authoredDraft());
      }
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"scope", "neighbor", "binding", "no-context", "inline"})
  void anEffectivePropagationDefaultCannotBypassTheExactNativeCorrectionBoundary(String variant) throws Exception {
    var h = new Harness(); h.begin();
    var original = withMode(input(), null);
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(original, null, bindings(), "1", "hash"));
    ProductCapabilityCaptureContext.bindDiscovery("run-" + h.chat, h.chat, h.store.get(h.chat).orElseThrow(), Map.of(),
        Set.of("/interactions/remote/failureMode"), ignored -> {});
    var tree = (ObjectNode) json.valueToTree(update(mode(variant.equals("scope")
        ? ServiceCallFailureMode.ERROR_SCOPE : ServiceCallFailureMode.INLINE_RESPONSE)));
    if (variant.equals("neighbor")) {
      var extra = (ObjectNode) json.valueToTree(input().flow().interactions().get(2));
      extra.put("failureMode", "INLINE_RESPONSE");
      ((com.fasterxml.jackson.databind.node.ArrayNode) tree.path("updateInteractions")).add(extra);
    }
    if (variant.equals("binding")) h.available.set(List.of(protocol(bindings().getFirst(), "kafka"), bindings().get(1)));
    if (variant.equals("no-context")) ProductCapabilityCaptureContext.unbind();
    try {
      var result = h.call("updateRequirementDraft", Map.of("changes", tree));
      assertEquals(variant.equals("inline"), result.path("accepted").asBoolean(), result.toString());
      if (!variant.equals("inline")) assertEquals(original, h.store.get(h.chat).orElseThrow().authoredDraft());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @Test
  void anAlreadyAcceptedLegacyDraftCannotPromoteAnInconsistentBrief() {
    var draft = RequirementCaptureProjection.toDraft(input(), null, bindings(), "1", "hash");
    var legacyTree = (ObjectNode) json.valueToTree(draft); legacyTree.putNull("authoredDraft");
    var legacy = json.convertValue(legacyTree, RequirementDraft.class);
    assertEquals(1, RequirementFailureConsistency.issues(legacy).size());
    var brief = new RequirementBrief("Process work", List.of(), List.of(), List.of(), List.of(), "");
    assertTrue(new RequirementBriefCoverageValidator().validate(legacy, brief).orElseThrow().contains("HTTP error responses"));
  }

  @Test
  void aCausalReopenCanApplyTheExactModeCorrectionAndRetainsOtherRepairAuthority() throws Exception {
    var h = new Harness(); h.begin();
    var draft = RequirementCaptureProjection.toDraft(input(), null, bindings(), "1", "hash");
    h.store.put(h.chat, draft);
    ProductCapabilityCaptureContext.bindDiscovery("run-" + h.chat, h.chat, draft, Map.of(),
        Set.of("/interactions/remote/failureMode", "/interactions/delivery/description"), ignored -> {});
    try {
      var result = h.call("updateRequirementDraft", Map.of("changes", update(mode(ServiceCallFailureMode.INLINE_RESPONSE))));
      assertTrue(result.path("accepted").asBoolean(), result.toString());
      assertEquals(input().facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @Test
  void aValidatedFreshAuthorAmendmentCanChooseASeparateErrorScope() throws Exception {
    var h = new Harness(); h.begin();
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(input(), null, bindings(), "1", "hash"));
    String source = "Use a distinct catch path with ERROR_SCOPE for remote failures.";
    var turn = h.archive.beginInput(h.chat, source, null); h.archive.resolveInput(h.chat, turn, source);
    h.store.beginTurn(h.chat, source);
    var update = update(mode(ServiceCallFailureMode.ERROR_SCOPE));
    var amended = new RequirementCaptureInput.DraftUpdate(update.addInteractions(), update.updateInteractions(),
        update.removeInteractionIds(), update.addTransitions(), update.removeTransitions(), update.addFacts(),
        update.updateFacts(), update.removeFactIds(), update.setCapabilities(), update.removeCapabilityInteractionIds(),
        update.addQuestions(), update.updateQuestions(), update.removeQuestionIds(), update.settings(),
        List.of(new RequirementCaptureInput.AmendmentInput("/interactions/remote/failureMode", turn.turnId(), source)));
    try {
      var result = h.call("updateRequirementDraft", Map.of("changes", amended));
      assertTrue(result.path("accepted").asBoolean(), result.toString());
      assertEquals(ServiceCallFailureMode.ERROR_SCOPE, h.store.get(h.chat).orElseThrow().authoredDraft().flow().interactions().get(1).failureMode());
      assertEquals(input().facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  static RequirementCaptureInput.DraftInput input() {
    return withNativeAnchors(RequirementFailureConsistencyTest.input(ServiceCallFailureMode.PROPAGATE));
  }
  static List<CatalogBindingHint> bindings() {
    return List.of(RequirementFailureConsistencyTest.binding("remote"), RequirementFailureConsistencyTest.binding("delivery"));
  }
  private static CatalogBindingHint protocol(CatalogBindingHint b, String protocol) {
    return new CatalogBindingHint(b.schemaVersion(), b.interactionId(), b.sourceFactId(), b.operationQuery(), b.systemId(),
        b.specificationGroupId(), b.specificationId(), b.integrationOperationId(), protocol, b.method(), b.path(), b.release(), b.observedAt(), b.evidenceRef());
  }
  static RequirementCaptureInput.DraftInput withMode(RequirementCaptureInput.DraftInput input, ServiceCallFailureMode mode) {
    var interactions = new java.util.ArrayList<>(input.flow().interactions());
    interactions.set(1, mode(mode));
    return new RequirementCaptureInput.DraftInput(new RequirementCaptureInput.FlowInput(interactions, input.flow().transitions()),
        input.facts(), input.capabilities(), input.openQuestions(), input.settings());
  }
  static RequirementCaptureInput.InteractionInput mode(ServiceCallFailureMode mode) {
    var i = input().flow().interactions().get(1);
    return new RequirementCaptureInput.InteractionInput(i.interactionId(), i.direction(), i.participant(), i.operation(), i.description(), mode, i.retryPolicy());
  }
  static RequirementCaptureInput.DraftUpdate update(RequirementCaptureInput.InteractionInput interaction) {
    return new RequirementCaptureInput.DraftUpdate(List.of(), List.of(interaction), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void laterNativeContradictionMustNotFreezeAnEarlierRejectedMode(boolean reload) throws Exception {
    var h = new Harness(); h.begin();
    try {
      var malformed = (ObjectNode) json.valueToTree(input());
      ((ObjectNode) malformed.at("/facts/2/elementIntent")).putNull("ownerInteractionId");
      var first = h.call("captureRequirementDraft", Map.of("draft", malformed));
      assertFalse(first.path("accepted").asBoolean(), first.toString());
      assertNull(h.store.repairConstraints(h.chat));
      var conflict = h.call("captureRequirementDraft", Map.of("draft", input()));
      assertFalse(conflict.path("accepted").asBoolean());
      assertEquals("FAILURE_MODE_CONFLICT", conflict.at("/issues/0/code").asText(), conflict.toString());
      var source = json.valueToTree(h.archive.read(h.chat));
      var old = h.artifacts.history(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION);
      if (reload) {
        var latest = old.getLast();
        var protection = h.artifacts.payload(latest, RequirementDraftStore.CaptureFactProtection.class);
        var legacy = (ObjectNode) protection.values().deepCopy();
        ((ObjectNode) legacy.at("/interactions/remote")).put("failureMode", "PROPAGATE");
        h.artifacts.append(new CompilationArtifacts.AppendCommand(latest.compilationId(),
            CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION, "1", "requirement-capture-protection", "1",
            new RequirementDraftStore.CaptureFactProtection(protection.sourceGeneration(), protection.sourceTurnId(),
                protection.authorText(), protection.facts(), legacy, protection.baseIdentity()), List.of(), latest.artifactId()));
        h.reload(); h.begin();
      }
      var forbidden = withMode(input(), ServiceCallFailureMode.ERROR_SCOPE);
      var refused = h.call("captureRequirementDraft", Map.of("draft", forbidden));
      assertFalse(refused.path("accepted").asBoolean(), refused.toString());
      assertTrue(h.store.get(h.chat).isEmpty());
      var corrected = withMode(input(), ServiceCallFailureMode.INLINE_RESPONSE);
      var accepted = h.call("captureRequirementDraft", Map.of("draft", corrected));
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      assertEquals(corrected, h.store.get(h.chat).orElseThrow().authoredDraft());
      assertTrue(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE"))
          .path("accepted").asBoolean());
      old.forEach(revision -> assertEquals(revision,
          h.artifacts.get(revision.compilationId(), revision.reference()).orElseThrow()));
      assertEquals(source.path("generation"), json.valueToTree(h.archive.read(h.chat)).path("generation"));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private class Harness {
    final String chat = "failure-consistency-" + UUID.randomUUID();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ConversationArchive archive = new ConversationArchive(blobs, json);
    final ConversationService conversations = mock(ConversationService.class);
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final AtomicReference<List<CatalogBindingHint>> available = new AtomicReference<>(bindings());
    RequirementDraftStore store;
    RequirementCaptureToolProvider provider;
    Harness() { when(conversations.archive()).thenReturn(archive); var source = archive.beginInput(chat, SOURCE, null);
      archive.resolveInput(chat, source, SOURCE); reload(); }
    void reload() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      var adapter = mock(RequirementDraftTool.class);
      when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenAnswer(call -> available.get());
      var tools = new RequirementCaptureTools(store, adapter, json);
      tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(json);
      provider = new RequirementCaptureToolProvider(tools, json);
    }
    void begin() { store.beginTurn(chat, SOURCE); ProductCapabilityCaptureContext.bindDiscovery("run-" + chat, chat, ignored -> {}); }
    JsonNode call(String tool, Object args) throws Exception {
      return json.readTree(provider.provideTools(null).toolExecutorByName(tool).execute(ToolExecutionRequest.builder()
          .id(UUID.randomUUID().toString()).name(tool).arguments(json.writeValueAsString(args)).build(), chat));
    }
  }
}
