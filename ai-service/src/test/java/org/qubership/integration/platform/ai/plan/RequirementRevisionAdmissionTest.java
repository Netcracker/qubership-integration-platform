package org.qubership.integration.platform.ai.plan;

import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementRevisionAdmissionTest {

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"async-api-trigger, false", "async-api-trigger, true",
      "unsupported-native, false", "unsupported-native, true"})
  void rejectedTypeDoesNotMakeItsUncheckedValuesImmutable(String invalidType, boolean reload) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Receive POST /tasks and call Delivery API. Proceed without IDS.");
      var initial = catalogDraft(invalidType);
      assertFalse(h.capture(initial).accepted());
      if (reload) {
        h.restartStore();
        h.store.beginTurn(h.chat, "Automatic correction.");
      }
      var retained = h.store.repairConstraints(h.chat).values();
      assertFalse(retained.path("capabilities").has("delivery"), retained.toString());
      var corrected = h.capture(catalogDraft(null));
      assertTrue(corrected.accepted(), corrected.toString());
      assertEquals(initial.flow(), h.store.get(h.chat).orElseThrow().authoredDraft().flow());
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
      h.resolveDelivery();
      var continued = h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE));
      assertTrue(continued.accepted(), continued.toString());
      assertEquals(RequirementDiscoveryDirective.CONTINUE, h.store.turnDirective(h.chat));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oldPendingConstraintsAreProjectedBeforeFeedbackAndAdmission(boolean acceptedBase) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      var opened = h.begin("Receive POST /tasks and call Delivery API. Proceed without IDS.");
      if (acceptedBase) {
        assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
        h.begin("Also call Delivery API after receiving a task.");
      }
      var invalid = catalogDraft("unsupported-native");
      var attempt = acceptedBase ? h.update(addDelivery(invalid.capabilities().getLast())) : h.capture(invalid);
      assertFalse(attempt.accepted());
      var previous = h.artifacts.latest(h.store.activeCompilationId(h.chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).orElseThrow();
      var protection = h.artifacts.payload(previous, RequirementDraftStore.CaptureFactProtection.class);
      var legacyValues = (com.fasterxml.jackson.databind.node.ObjectNode) protection.values().deepCopy();
      var legacy = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(invalid.capabilities().getLast());
      legacy.putNull("capabilityKey");
      legacyValues.withObject("/capabilities").set("delivery", legacy);
      legacyValues.withObject("/interactions").withObject("/delivery").put("interactionId", "delivery");
      legacyValues.withObject("/interactions").withObject("/delivery").putObject("retryPolicy")
          .putNull("retryCount").putNull("retryDelayMs");
      h.artifacts.append(new CompilationArtifacts.AppendCommand(previous.compilationId(),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION, "1", "requirement-capture-protection", "1",
          new RequirementDraftStore.CaptureFactProtection(protection.sourceGeneration(), protection.sourceTurnId(),
              protection.authorText(), protection.facts(), legacyValues, protection.baseIdentity()), List.of(), previous.artifactId()));
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      assertFalse(h.store.repairConstraints(h.chat).values().path("capabilities").has("delivery"));
      assertFalse(h.store.repairConstraints(h.chat).values().path("interactions").path("delivery").has("retryPolicy"));
      var corrected = acceptedBase ? h.update(addDelivery(null)) : h.capture(catalogDraft(null));
      assertTrue(corrected.accepted(), corrected.toString());
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
      assertEquals(2, h.store.get(h.chat).orElseThrow().authoredDraft().flow().interactions().size());
      if (acceptedBase) {
        assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(quoted(org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact(
                "entry", "http-trigger"), opened.turnId(), "POST /tasks")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null)).accepted());
      }
      h.resolveDelivery();
      var continued = h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE));
      assertTrue(continued.accepted(), continued.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"method", "participant", "transition", "fact", "native"})
  void releasingRejectedTypeRetainsIndependentRequirements(String changed) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Receive POST /tasks and call Delivery API. Proceed without IDS.");
      assertFalse(h.capture(catalogDraft("unsupported-native")).accepted());
      var json = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(catalogDraft(null));
      switch (changed) {
        case "method" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.at("/capabilities/0")).put("httpMethod", "GET");
        case "participant" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.at("/flow/interactions/1")).put("participant", "Other API");
        case "transition" -> ((com.fasterxml.jackson.databind.node.ArrayNode) json.at("/flow/transitions")).removeAll();
        case "fact" -> ((com.fasterxml.jackson.databind.node.ArrayNode) json.path("facts")).remove(0);
        case "native" -> ((com.fasterxml.jackson.databind.node.ArrayNode) json.path("capabilities")).removeAll();
        default -> throw new AssertionError(changed);
      }
      var rejected = h.capture(h.mapper.treeToValue(json, DraftInput.class));
      if ("native".equals(changed)) {
        assertFalse(rejected.accepted(), changed + ": " + rejected);
        assertTrue(h.store.get(h.chat).isEmpty());
        assertTrue(h.capture(catalogDraft(null)).accepted());
      } else if ("transition".equals(changed)) {
        assertTrue(rejected.accepted(), rejected.toString());
        assertEquals(List.of(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
      } else {
        assertTrue(rejected.accepted(), changed + ": " + rejected);
        assertEquals(h.mapper.treeToValue(json, DraftInput.class),
            h.store.get(h.chat).orElseThrow().authoredDraft());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedNestedRecordProtectsOnlyItsValidatedSibling(boolean validDelay) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Receive POST /tasks and call Delivery API. Proceed without IDS.");
      var base = catalogDraft(null);
      var invalid = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base);
      ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/flow/interactions/1"))
          .putObject("retryPolicy").put("retryCount", 9).put("retryDelayMs", validDelay ? 5000 : 1);
      assertFalse(h.capture(h.mapper.treeToValue(invalid, DraftInput.class)).accepted());
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      if (validDelay) {
        var changed = invalid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.at("/flow/interactions/1/retryPolicy"))
            .put("retryCount", 2).put("retryDelayMs", 6000);
        var admitted = h.capture(h.mapper.treeToValue(changed, DraftInput.class));
        assertTrue(admitted.accepted(), admitted.toString());
        var retry = h.store.get(h.chat).orElseThrow().authoredDraft().flow().interactions().get(1).retryPolicy();
        assertEquals(2, retry.retryCount());
        assertEquals(6000, retry.retryDelayMs());
        return;
      }
      var corrected = h.capture(base);
      assertTrue(corrected.accepted(), corrected.toString());
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
    }
  }

  @Test
  void pendingProjectionKeepsExplicitEmptyCollectionsAndDropsInvalidRecordDependencies() {
    var mapper = new ObjectMapper();
    var values = mapper.createObjectNode();
    values.putObject("settings").putArray("selectedValues");
    var interactions = values.putObject("interactions");
    interactions.putObject("invalid").putNull("interactionId").put("operation", "Unchecked operation");
    interactions.putObject("entry").put("interactionId", "entry").put("direction", "INBOUND");
    values.putObject("capabilities").putObject("invalid").put("interactionId", "invalid")
        .put("capabilityKey", "http-sender").put("httpMethod", "POST");
    values.putObject("transitions").putArray("entry").add("invalid").add("delivery");
    var projected = RequirementRevisionAdmission.pendingValues(values);
    assertTrue(projected.at("/settings/selectedValues").isArray());
    assertTrue(projected.at("/settings/selectedValues").isEmpty());
    assertFalse(projected.path("interactions").has("invalid"));
    assertFalse(projected.path("capabilities").has("invalid"));
    assertEquals(List.of("delivery"), mapper.convertValue(projected.at("/transitions/entry"), List.class));
    assertEquals("Unchecked operation", values.at("/interactions/invalid/operation").asText(), "Projection is read-only");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void persistedProposalCacheTracksTheEffectivePendingContract(boolean projectionChanged) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Receive POST /tasks and call Delivery API. Proceed without IDS.");
      var invalid = catalogDraft("unsupported-native");
      assertFalse(h.capture(invalid).accepted());
      var protectionRevision = h.artifacts.latest(h.store.activeCompilationId(h.chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).orElseThrow();
      var protection = h.artifacts.payload(protectionRevision, RequirementDraftStore.CaptureFactProtection.class);
      var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) protection.values().deepCopy();
      legacy.withObject("/capabilities").withObject("/entry").put("interactionId", "entry");
      legacy.withObject("/interactions").withObject("/entry").put("interactionId", "entry");
      if (projectionChanged) legacy.withObject("/capabilities").set("delivery", h.mapper.valueToTree(new CapabilityInput(
          "delivery", null, null, HttpMethod.POST, "/unchecked-target", null, null, null)));
      h.artifacts.append(new CompilationArtifacts.AppendCommand(protectionRevision.compilationId(),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION, "1", "requirement-capture-protection", "1",
          new RequirementDraftStore.CaptureFactProtection(protection.sourceGeneration(), protection.sourceTurnId(),
              protection.authorText(), protection.facts(), legacy, protection.baseIdentity()),
          List.of(), protectionRevision.artifactId()));
      var proposed = projectionChanged ? catalogDraft(null) : invalid;
      var previousAttempt = h.artifacts.latest(h.store.activeCompilationId(h.chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      var issue = new RequirementCaptureResult.CaptureIssue(projectionChanged
          ? "AUTHOR_AMENDMENT_REQUIRED" : "UNSUPPORTED_CAPABILITY", "/capabilities/delivery", "delivery", null,
          "Previously rejected under the saved preservation contract.");
      h.artifacts.append(new CompilationArtifacts.AppendCommand(previousAttempt.compilationId(),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", "requirement-capture-tools", "1",
          new RequirementDraftStore.RequirementCaptureAttempt(protection.sourceGeneration(), protection.sourceTurnId(),
              protection.authorText(), h.store.captureMutationScope(h.chat), "captureRequirementDraft", proposed, false,
              List.of(issue), null, org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(java.util.Map.of())),
          List.of(), previousAttempt.artifactId()));
      var fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(
          h.mapper, h.artifacts, h.sessions);
      var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(
          fingerprints, h.artifacts, h.sessions);
      new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, feedback)
          .repeatedRequirementIssue(h.chat, h.store.captureMutationScope(h.chat), List.of(
              new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
                  issue.code(), issue.path(), issue.message(), issue.entityId())));
      int spent = fingerprints.softCreditsUsed(h.chat);
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      var restoredFeedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(
          fingerprints, h.artifacts, h.sessions);
      h.tools = new RequirementCaptureTools(h.store, h.bindings, h.mapper,
          new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, restoredFeedback),
          restoredFeedback, null);
      if (projectionChanged) {
        var changed = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(proposed);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.at("/capabilities/0")).put("httpMethod", "GET");
        var methodChange = h.mapper.treeToValue(changed, DraftInput.class);
        var admitted = h.capture(methodChange);
        assertTrue(admitted.accepted(), admitted.toString());
        assertEquals(HttpMethod.GET, h.store.get(h.chat).orElseThrow().authoredDraft().capabilities().getFirst().httpMethod());
        spent = fingerprints.softCreditsUsed(h.chat);
      } else {
        assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
            () -> h.capture(proposed));
      }
      assertEquals(spent, fingerprints.softCreditsUsed(h.chat), "Reload and revalidation grant no new repair credits");
    }
  }

  private static DraftInput catalogDraft(String invalidType) {
    var base = withNativeAnchors(httpDraft(List.of(fact("goal", "Create tasks."))));
    var capabilities = new java.util.ArrayList<>(base.capabilities());
    if (invalidType != null) capabilities.add(new CapabilityInput("delivery", invalidType, null,
        HttpMethod.POST, "/unchecked-target", null, null, null));
    return new DraftInput(new FlowInput(List.of(base.flow().interactions().getFirst(),
        new InteractionInput("delivery", Direction.OUTBOUND, "Delivery API", "Deliver task", null, null, null)),
        List.of(new TransitionInput("entry", "delivery"))), base.facts(), capabilities, List.of(), base.settings());
  }

  private static DraftUpdate addDelivery(CapabilityInput invalidCapability) {
    return new DraftUpdate(List.of(new InteractionInput("delivery", Direction.OUTBOUND,
        "Delivery API", "Deliver task", null, null, null)), List.of(), List.of(),
        List.of(new TransitionInput("entry", "delivery")), List.of(), List.of(), List.of(), List.of(),
        invalidCapability == null ? List.of() : List.of(invalidCapability), List.of(), List.of(), List.of(), List.of(), null);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false, invalid recipient", "true, invalid recipient",
      "false, invalid/recipient", "true, invalid/recipient", "false, invalid~1recipient", "true, invalid~1recipient"})
  void rejectedInteractionIdentityDoesNotProtectItsTransitions(boolean recreateStore, String rejectedId) throws Exception {
    var h = new Harness();
    h.begin("Receive POST /tasks and send it to Task API and Audit API. Proceed without IDS.");
    try (var ignored = ToolSession.open(h.chat)) {
      var rejected = h.capture(parallelHttpDraft(rejectedId));
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue -> "INVALID_VALUE".equals(issue.code())));
      var retained = h.store.repairConstraints(h.chat).values().path("transitions").path("entry");
      assertTrue(retained.toString().contains("audit"));
      if (recreateStore) {
        h.restartStore();
        h.store.beginTurn(h.chat, "Automatic correction.");
      }
      var corrected = h.capture(parallelHttpDraft("delivery"));
      assertTrue(corrected.accepted(), corrected.toString());
      assertFalse(retained.toString().contains(rejectedId));
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      var removed = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
          List.of(new TransitionInput("entry", "audit")), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      var kept = h.update(removed);
      assertTrue(kept.accepted(), kept.toString());
      assertTrue(h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions()
          .contains(new TransitionInput("entry", "audit")));
    }
  }

  @Test
  void historicalRejectedValuesReleaseTransitionsToInvalidIdentities() {
    var values = new ObjectMapper().createObjectNode();
    values.putObject("interactions").putNull("invalid recipient");
    var transitions = values.putObject("transitions");
    transitions.putArray("entry").add("invalid recipient").add("audit");
    transitions.putArray("invalid recipient").add("delivery");
    var issues = RequirementRevisionAdmission.validate(null, parallelHttpDraft("delivery"), List.of(), values,
        java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), fact -> false);
    assertTrue(issues.isEmpty(), issues.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"method", "operation", "fact-text", "fact-kind", "element-property", "element-type", "folder", "ids-choice"})
  void rejectedReplacementCannotInvalidateAcceptedStateAcrossRetryAndReload(String field) throws Exception {
    for (boolean earlyRejection : List.of(false, true)) {
      var h = new Harness();
      try (var ignored = ToolSession.open(h.chat)) {
        h.begin("Create tasks at /tasks in Tasks. Proceed without IDS.");
        var initial = httpDraft(List.of(fact("goal", "Create tasks.")));
        if (field.startsWith("element-")) {
          var intent = new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
              "entry", null, null, null,
              List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("contextPath", "/tasks")), List.of());
          initial = httpDraft(List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL,
              Polarity.POSITIVE, "Create tasks. HTTP trigger contextPath /tasks", null, null, intent)));
        }
        var base = withNativeAnchors(new DraftInput(initial.flow(), initial.facts(), initial.capabilities(), List.of(),
            new DraftSettings(false, null, "Tasks")));
        assertTrue(h.capture(base).accepted());
        var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
        var json = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(new DraftUpdate(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), null));
        String path;
        switch (field) {
          case "method" -> {
            var value = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base.capabilities().getFirst());
            value.put("httpMethod", "GET");
            json.withArray("setCapabilities").add(value);
            path = "/capabilities/entry/httpMethod";
          }
          case "operation" -> {
            var value = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base.flow().interactions().getFirst());
            value.put("operation", "Changed operation");
            json.withArray("updateInteractions").add(value);
            path = "/interactions/entry/operation";
          }
          case "element-property", "element-type" -> {
            var value = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base.facts().getFirst());
            if (field.equals("element-type")) {
              ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("elementIntent")).put("elementType", "kafka-sender");
              ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/elementIntent/requestedProperties/0"))
                  .put("key", "brokers").put("value", "localhost:9092");
            } else {
              ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/elementIntent/requestedProperties/0")).put("value", "/changed");
            }
            json.withArray("updateFacts").add(value);
            path = "/facts/goal/elementIntent";
          }
          case "fact-text", "fact-kind" -> {
            var value = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base.facts().getFirst());
            String key = field.equals("fact-text") ? "text" : "kind";
            value.put(key, key.equals("text") ? "Changed goal." : "BEHAVIOR");
            json.withArray("updateFacts").add(value);
            path = "/facts/goal/" + key;
          }
          default -> {
            var value = h.mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base.settings());
            if (field.equals("folder")) value.put("targetFolderPath", "Changed Folder");
            else value.put("idsRequested", true);
            json.set("settings", value);
            path = "/settings/" + (field.equals("folder") ? "targetFolderPath" : "idsRequested");
          }
        }
        if (earlyRejection) json.withArray("addFacts").add(h.mapper.valueToTree(base.facts().getFirst()));
        else json.withArray("amendments").add(h.mapper.valueToTree(new AmendmentInput(path, "consumed-source", "Create tasks.")));
        assertFalse(h.update(h.mapper.treeToValue(json, DraftUpdate.class)).accepted());
        assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
        for (boolean reload : List.of(false, true)) {
          if (reload) h.restartStore();
          h.store.beginTurn(h.chat, "Automatic correction.");
          assertTrue(h.store.validateCurrent(h.chat).isEmpty(), field + ": " + h.store.validateCurrent(h.chat));
          assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null)).accepted());
          assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
          assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
        }
      }
    }
  }

  @Test
  void roleAnswerNamesMissingAmendmentChildrenAndAcceptsTheExistingNullParent() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks with RBAC. Proceed without IDS.");
      var original = new FactInput("access-control", List.of("entry"), FactKind.CONSTRAINT,
          Polarity.POSITIVE, "Access control: RBAC.");
      var base = httpDraft(List.of(original));
      assertTrue(h.capture(base).accepted());
      String quote = "Allow ROLE_CIP_ADMIN.";
      var source = h.begin(quote);
      var intent = new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
          "entry", null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", "[\"ROLE_CIP_ADMIN\"]")),
          List.of());
      var replacement = new FactInput(original.sourceFactId(), original.interactionIds(), original.kind(),
          original.polarity(), original.text(), null, null, intent);
      for (String field : List.of("requestedProperties", "exactValues")) {
        String path = "/facts/access-control/elementIntent/" + field;
        var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(replacement), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            List.of(new AmendmentInput(path, source.turnId(), quote)));
        var result = h.update(update);
        assertFalse(result.accepted());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_AUTHOR_AMENDMENT")
            && issue.path().equals("/changes/amendments/0/path") && issue.message().contains(path)
            && issue.message().contains("/facts/access-control/elementIntent")), result.toString());
        assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
      }
      var corrected = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(replacement), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
          List.of(new AmendmentInput("/facts/access-control/elementIntent", source.turnId(), quote)));
      assertTrue(h.update(corrected).accepted());
      assertEquals(replacement, h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst());
      assertFalse(h.update(corrected).accepted(), "An admitted source cannot authorize another amendment");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void automaticScriptRepairCannotRemoveAnAcceptedExactLiteral(boolean reload) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks, then return exact plain text a=b from a Script. Proceed without IDS.");
      var intent = new RequirementElementIntent("response-script", "script",
          RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, "entry", null, List.of(), List.of("a=b"));
      var original = new FactInput("response", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Return exact plain text a=b from a Script.", null, null, intent);
      var base = withNativeAnchors(httpDraft(List.of(original)));
      assertTrue(h.capture(base).accepted());
      var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
      if (reload) h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      var cleared = new FactInput(original.sourceFactId(), original.interactionIds(), original.kind(),
          original.polarity(), original.text(), null, null,
          new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
              intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(),
              intent.requestedName(), intent.requestedProperties(), List.of()));

      var result = h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(cleared), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

      assertFalse(result.accepted(), result.toString());
      assertTrue(result.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
          && "/changes/facts/response".equals(issue.path()) && "response".equals(issue.entityId())), result.toString());
      assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
      assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/flow/interactions/0/operation", "/flow/interactions/missing/operation",
      "/flow/interactions/entry/interactionId", "/flow/settings/idsRequested"})
  void flowPathNormalizationDoesNotAuthorizeUnknownOrIdentityFields(String path) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      var base = httpDraft(List.of(fact("goal", "Create tasks.")));
      assertTrue(h.capture(base).accepted());
      var source = h.begin("Change the route to /updated.");
      var update = new DraftUpdate(List.of(), List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "POST /updated", "Task endpoint", null, null)), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
          List.of(new AmendmentInput(path, source.turnId(), "Change the route to /updated.")));
      var result = h.update(update);
      assertFalse(result.accepted());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_AUTHOR_AMENDMENT")));
      assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
    }
  }

  @Test
  void routeAnswerAcceptsSourceLinkedFlowPathsAndRetainsOtherRequirements() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Receive GET on an internal route. I will supply its path next. Proceed without IDS.");
      var base = httpDraft(List.of(fact("goal", "Return Hello world.")));
      var partial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "GET on an internal route", "Return Hello world.", null, null)), List.of()),
          base.facts(), List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM,
              HttpMethod.GET, null, null, null, null)),
          List.of(new QuestionInput("route-path", List.of("entry"), "What is the route path?")), base.settings());
      assertTrue(h.capture(partial).accepted());
      var source = h.begin("The internal GET route path is /verify/hello. Keep earlier requirements unchanged.");
      var update = new DraftUpdate(List.of(), List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "GET /verify/hello", "Return Hello world. Route: /verify/hello.", null, null)),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
              "/verify/hello", null, null, null)), List.of(), List.of(), List.of(), List.of("route-path"), null,
          List.of(new AmendmentInput("/flow/interactions/entry/operation", source.turnId(),
                  "The internal GET route path is /verify/hello."),
              new AmendmentInput("/flow/interactions/entry/description", source.turnId(),
                  "The internal GET route path is /verify/hello.")));
      var result = h.update(update);
      assertTrue(result.accepted(), result.toString());
      var admitted = h.store.get(h.chat).orElseThrow().authoredDraft();
      assertEquals(partial.facts(), admitted.facts());
      assertEquals("/verify/hello", admitted.capabilities().getFirst().path());
      assertTrue(admitted.openQuestions().isEmpty());
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
      assertFalse(h.update(update).accepted());
      assertEquals(admitted, h.store.get(h.chat).orElseThrow().authoredDraft());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void idsAnswerKeepsItsSourceAndResolvesOnlyTheBoundQuestion(boolean requested) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks.");
      var base = httpDraft(List.of(fact("goal", "Create tasks.")));
      String idsQuestion = org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.IDS_PATH_CHOICE;
      var other = new QuestionInput("endpoint", List.of("entry"), "Which endpoint should receive the request?");
      var partial = new DraftInput(base.flow(), base.facts(), base.capabilities(),
          List.of(new QuestionInput(idsQuestion, List.of(), "Show IDS?"), other), new DraftSettings(null, null));
      assertTrue(h.capture(partial).accepted());
      var source = h.begin(requested ? "yes" : "no");
      h.store.recordIdsChoice(h.chat, requested);
      var decided = h.store.get(h.chat).orElseThrow();
      assertEquals(requested, decided.idsRequested());
      assertEquals(requested, decided.authoredDraft().settings().idsRequested());
      assertEquals(List.of(other), decided.authoredDraft().openQuestions());
      assertEquals(base.facts(), decided.authoredDraft().facts());
      assertFalse(decided.readyForPlan());
      assertTrue(h.store.latestRevision(h.chat).orElseThrow().inputs().size() > 0);
      assertNotNull(h.archive.read(h.chat).activeTurns().stream()
          .filter(turn -> turn.id().equals(source.turnId())).findFirst().orElseThrow().consumedDraftReference());
      h.restartStore();
      assertEquals(decided, h.store.get(h.chat).orElseThrow());
      h.store.beginTurn(h.chat, "Automatic correction.");
      var erased = h.capture(new DraftInput(base.flow(), base.facts(), base.capabilities(), List.of(other),
          new DraftSettings(null, null)));
      assertFalse(erased.accepted());
      assertEquals(requested, h.store.get(h.chat).orElseThrow().idsRequested());
      var reopened = h.capture(new DraftInput(base.flow(), base.facts(), base.capabilities(),
          partial.openQuestions(), decided.authoredDraft().settings()));
      assertFalse(reopened.accepted());
      assertEquals(decided, h.store.get(h.chat).orElseThrow());
    }
  }

  @Test
  void nativeRepairEvidenceMustIdentifyTheSameStableRecord() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks and preserve inputs. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks."),
          fact("sibling", "Preserve inputs.")))).accepted());
      var draft = h.store.get(h.chat).orElseThrow();
      var details = org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty();
      assertEquals("/facts/goal/text", RequirementDraftStore.requirementRepairPath(draft,
          new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
              "SOURCE_LITERAL_MISMATCH", "Restore the saved requirement.", true, details, "goal", "/facts[]/text")));
      assertNull(RequirementDraftStore.requirementRepairPath(draft,
          new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
              "SOURCE_LITERAL_MISMATCH", "Restore the saved requirement.", true, details, "goal", "/facts/sibling/text")));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void confirmedAnswerSurvivesRetriesAndUnrelatedMessages(boolean newMessage) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create an external endpoint with NONE. Proceed without IDS.");
      assertTrue(h.capture(policyDraft(null)).accepted());
      h.select(Decision.KEEP_REQUESTED);
      assertTrue(h.update(editPolicy(Decision.KEEP_REQUESTED, false)).accepted());
      var accepted = h.store.latestRevision(h.chat).orElseThrow();
      if (newMessage) {
        h.begin("Also preserve the request ID.");
      } else {
        h.store.beginTurn(h.chat, "Automatic correction.");
      }
      var rejected = h.update(editPolicy(null, true));
      assertFalse(rejected.accepted(), rejected.toString());
      var feedback = rejected.issues().stream().filter(issue -> "CONFIRMED_ANSWER_CHANGED".equals(issue.code()))
          .findFirst().orElseThrow().message();
      assertTrue(feedback.contains("\"decision\":\"KEEP_REQUESTED\""), feedback);
      assertTrue(feedback.contains("Null does not instruct you to clear an accepted value"), feedback);
      assertEquals(accepted.reference(), h.store.latestRevision(h.chat).orElseThrow().reference());
      assertEquals(Decision.KEEP_REQUESTED, h.store.get(h.chat).orElseThrow()
          .authoredDraft().facts().getLast().policyConflict().decision());
      assertTrue(h.store.get(h.chat).orElseThrow().openQuestions().isEmpty());
    }
  }

  @Test
  void rejectedProjectionCanNarrowAfterRestart() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks, preserve request IDs, and keep inputs. Proceed without IDS.");
      assertFalse(h.capture(httpDraft(List.of(fact("invalid id", "Create tasks.")))).accepted());
      var second = httpDraft(List.of(fact("goal", "Create tasks."), fact("retain-id", "Preserve request IDs."),
          new FactInput("invalid-ref", List.of("missing"), FactKind.GOAL, Polarity.POSITIVE, "Keep inputs.")));
      assertFalse(h.capture(second).accepted());
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      var narrowed = h.capture(httpDraft(List.of(fact("goal", "Create tasks."))));
      assertTrue(narrowed.accepted(), narrowed.toString());
      assertEquals(List.of("goal"), h.store.get(h.chat).orElseThrow().authoredDraft().facts().stream()
          .map(FactInput::sourceFactId).toList());
      var replaced = h.capture(withNativeAnchors(httpDraft(List.of(second.facts().get(0), second.facts().get(1),
          fact("invalid-ref", "Keep inputs.")))));
      assertFalse(replaced.accepted(), replaced.toString());
      assertTrue(replaced.issues().stream().anyMatch(issue -> "DRAFT_ALREADY_EXISTS".equals(issue.code())),
          replaced.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void identicalTextOnDistinctOwnersRemainsSeparate(boolean elementOwner) {
    var first = fact("first", "Record status.");
    var second = new FactInput("second", List.of("delivery"), first.kind(), first.polarity(), first.text());
    if (elementOwner) {
      first = new FactInput(first.sourceFactId(), first.interactionIds(), first.kind(), first.polarity(),
          first.text(), null, null, new RequirementElementIntent("first-node", "log-record",
              RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, "First", List.of(), List.of()));
      second = new FactInput(second.sourceFactId(), first.interactionIds(), first.kind(), first.polarity(),
          first.text(), null, null, new RequirementElementIntent("second-node", "log-record",
              RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, "Second", List.of(), List.of()));
    }
    assertEquals(List.of(first, second), RequirementRevisionAdmission.accumulate(List.of(first), List.of(second)));
  }

  @Test
  void incompleteFactsDoNotAcquireASharedIdentity() {
    var first = new FactInput("first", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, null);
    var second = new FactInput("second", first.interactionIds(), first.kind(), first.polarity(), null);
    assertEquals(List.of(first, second), RequirementRevisionAdmission.accumulate(List.of(first), List.of(second)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedIdentityLossCannotAddAlternativeFactConstraints(boolean recreateStore) throws Exception {
    var h = new Harness();
    h.begin("Create tasks, record completion, and preserve request IDs. Proceed without IDS.");
    java.util.function.BiFunction<String, String, FactInput> event = (id, ref) ->
        new FactInput(id, List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Record completion.",
            null, null, new RequirementElementIntent(ref, "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
                "entry", null, "entry", null, List.of(), List.of()));
    try (var ignored = ToolSession.open(h.chat)) {
      assertFalse(h.capture(withNativeAnchors(httpDraft(List.of(fact("goal", "Create tasks."),
          event.apply("completion", "invalid event"))))).accepted());
      var renamed = withNativeAnchors(httpDraft(List.of(fact("goal", "Create tasks."),
          event.apply("renamed-completion", "emit"), fact("invalid id", "Preserve request IDs."))));
      assertFalse(h.capture(renamed).accepted());
      assertFalse(h.store.rejectedCaptureFacts(h.chat).stream()
          .anyMatch(fact -> "renamed-completion".equals(fact.sourceFactId())),
          "Rejected identity loss must not create another mandatory version of the same fact");
      if (recreateStore) {
        h.restartStore();
        h.store.beginTurn(h.chat, "Automatic correction.");
      }
      var corrected = withNativeAnchors(httpDraft(List.of(fact("goal", "Create tasks."),
          event.apply("completion", "emit"), fact("retain-id", "Preserve request IDs."))));
      var admitted = h.capture(corrected);
      assertTrue(admitted.accepted(), admitted.toString());
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
    }
  }

  @Test
  void derivedWriterCannotReplaceAuthoredRequirements() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var accepted = h.store.get(h.chat).orElseThrow();
      var replacement = RequirementCaptureProjection.toDraft(httpDraft(List.of(fact("goal", "Drop tasks."))),
          accepted, List.of(), null, null);
      assertThrows(IllegalArgumentException.class, () -> h.store.put(h.chat, replacement));
      assertEquals(accepted, h.store.get(h.chat).orElseThrow());
    }
  }

  @Test
  void anAuthorUrlAmendmentDoesNotPermitCollateralFactChanges() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks at /tasks. Proceed without IDS.");
      var initial = httpDraft(List.of(fact("goal", "Create tasks."), fact("retain-id", "Preserve request IDs.")));
      assertTrue(h.capture(initial).accepted());
      var source = h.begin("Change the endpoint URL to /new-tasks.");
      var replacement = new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
          "/new-tasks", null, null, null);
      var evidence = List.of(new AmendmentInput("/capabilities/entry/path", source.turnId(),
          "Change the endpoint URL to /new-tasks."));
      var collateral = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("goal", "Drop tasks.")), List.of(), List.of(replacement), List.of(), List.of(), List.of(),
          List.of(), null, evidence);
      assertFalse(h.update(collateral).accepted());
      var focused = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(replacement), List.of(), List.of(), List.of(), List.of(), null, evidence);
      assertTrue(h.update(focused).accepted());
      assertEquals(initial.facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
      assertEquals("/new-tasks", h.store.get(h.chat).orElseThrow().authoredDraft().capabilities().getFirst().path());
      assertFalse(h.store.isApproved(h.chat, h.store.latestRevision(h.chat).orElseThrow().reference()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-source", "invented-quote", "broad-path", "consumed-source"})
  void amendmentsRequireFreshSourceAndAnExistingScopedPath(String defect) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      var old = h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var current = h.begin("Drop tasks.");
      var evidence = new AmendmentInput(defect.equals("broad-path") ? "/facts" : "/facts/goal/text",
          defect.equals("missing-source") ? "missing" : defect.equals("consumed-source") ? old.turnId() : current.turnId(),
          defect.equals("invented-quote") ? "Invented amendment." : "Drop tasks.");
      var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("goal", "Drop tasks.")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          null, List.of(evidence));
      var result = h.update(update);
      assertFalse(result.accepted(), result.toString());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_AUTHOR_AMENDMENT")));
      assertEquals("Create tasks.", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().text());
    }
  }

  @Test
  void oneAuthorAmendmentUpdatesDuplicatedValuesAndPreservesTheConfirmedAnswer() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create an external endpoint at /tasks with NONE. If RBAC is selected, use role qip-viewer. Proceed without IDS.");
      var initial = policyDraft(null);
      var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
          Polarity.POSITIVE, "External endpoint at /tasks.");
      var roles = new FactInput("roles", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "If RBAC is selected, use role qip-viewer.", null, null,
          new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
              "entry", null, null, null,
              List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", "[\"qip-viewer\"]")),
              List.of()));
      var flow = new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "POST /tasks", "External task endpoint at /tasks.", null, null)), List.of());
      assertTrue(h.capture(new DraftInput(flow,
          List.of(initial.facts().getFirst(), visibility, roles, initial.facts().getLast()),
          initial.capabilities(), initial.openQuestions(), initial.settings())).accepted());
      h.select(Decision.USE_RECOMMENDED);
      assertTrue(h.update(editPolicy(Decision.USE_RECOMMENDED, false)).accepted());
      String quote = "Change only the endpoint URL to /new-tasks.";
      var source = h.begin(quote);
      var update = new DraftUpdate(List.of(), List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "POST /new-tasks", "External task endpoint at /new-tasks.", null, null)), List.of(), List.of(), List.of(),
          List.of(), List.of(new FactInput(visibility.sourceFactId(), visibility.interactionIds(), visibility.kind(),
              visibility.polarity(), "External endpoint at /new-tasks.")), List.of(),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/new-tasks", null, null, null)), List.of(), List.of(), List.of(), List.of(), null,
          List.of("/capabilities/entry/path", "/interactions/entry/operation",
              "/interactions/entry/description", "/facts/visibility/text")
              .stream().map(path -> new AmendmentInput(path, source.turnId(), quote)).toList());
      assertTrue(h.update(update).accepted());
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      h.restartStore();
      RequirementDraft selected = h.store.get(h.chat).orElseThrow();
      assertEquals("External task endpoint at /new-tasks.", selected.flow().interactions().getFirst().description());
      var brief = RequirementBriefProjector.project(
          new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
              "Create tasks", List.of(), List.of(), List.of(), List.of(), "Create tasks", null,
              selected.planningText(), selected.facts()).withFlow(selected.flow()));
      assertEquals("/new-tasks", brief.entryPoints().getFirst().path());
      assertEquals("POST /new-tasks", brief.entryPoints().getFirst().operation());
      String downstream = org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText.format(brief);
      assertTrue(downstream.contains("method=POST path=/new-tasks"), downstream);
      assertFalse(downstream.contains("POST /tasks"), downstream);
      assertFalse(downstream.contains("at /tasks."), downstream);
      assertEquals(Decision.USE_RECOMMENDED, h.store.confirmedAnswers(h.chat).getFirst().policy().decision());
      assertEquals(initial.facts().getFirst(), selected.authoredDraft().facts().getFirst());
    }
  }

  @Test
  void anExplicitAnswerAmendmentRetainsItsOwnSourceProvenance() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create an external endpoint with NONE. Proceed without IDS.");
      assertTrue(h.capture(policyDraft(null)).accepted());
      h.select(Decision.KEEP_REQUESTED);
      assertTrue(h.update(editPolicy(Decision.KEEP_REQUESTED, false)).accepted());
      var first = h.store.confirmedAnswers(h.chat).getFirst();
      assertEquals("choice", first.questionId());
      assertEquals(List.of("entry"), first.interactionIds());
      assertFalse(first.sourceInputs().isEmpty());
      var source = h.begin("Change my answer: use RBAC.");
      var edit = editPolicy(Decision.USE_RECOMMENDED, false);
      var amendment = new DraftUpdate(edit.addInteractions(), edit.updateInteractions(), edit.removeInteractionIds(),
          edit.addTransitions(), edit.removeTransitions(), edit.addFacts(), edit.updateFacts(), edit.removeFactIds(),
          edit.setCapabilities(), edit.removeCapabilityInteractionIds(), edit.addQuestions(), edit.updateQuestions(),
          List.of(), null, List.of(new AmendmentInput("/facts/policy/policyConflict/decision", source.turnId(),
              "Change my answer: use RBAC.")));
      var refused = h.update(amendment);
      assertFalse(refused.accepted(), refused.toString());
      assertEquals(first, h.store.confirmedAnswers(h.chat).getFirst());
      var selectedSource = h.select(Decision.USE_RECOMMENDED);
      String selectedQuote = h.archive.read(h.chat).activeTurns().stream()
          .filter(turn -> turn.id().equals(selectedSource.turnId())).findFirst().orElseThrow().message().content();
      var selectedAmendment = new DraftUpdate(amendment.addInteractions(), amendment.updateInteractions(), amendment.removeInteractionIds(),
          amendment.addTransitions(), amendment.removeTransitions(), amendment.addFacts(), amendment.updateFacts(), amendment.removeFactIds(),
          amendment.setCapabilities(), amendment.removeCapabilityInteractionIds(), amendment.addQuestions(), amendment.updateQuestions(),
          amendment.removeQuestionIds(), null, List.of(new AmendmentInput("/facts/policy/policyConflict/decision", selectedSource.turnId(),
              selectedQuote)));
      var changed = h.update(selectedAmendment);
      assertTrue(changed.accepted(), changed.toString());
      var second = h.store.confirmedAnswers(h.chat).getFirst();
      assertEquals(Decision.USE_RECOMMENDED, second.policy().decision());
      assertNotEquals(first.admittedRevision(), second.admittedRevision());
      assertNotEquals(first.sourceInputs(), second.sourceInputs());
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      assertEquals(second, h.store.confirmedAnswers(h.chat).getFirst());
      assertFalse(h.update(editPolicy(null, true)).accepted());
    }
  }

  @Test
  void obsoleteSourceCannotCommitOrConsumeANewerUserMessage() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var before = h.store.latestRevision(h.chat).orElseThrow();
      var newer = h.archive.beginInput(h.chat, "Preserve request IDs.", null);
      h.archive.resolveInput(h.chat, newer, "Preserve request IDs.");
      var result = h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null));
      assertFalse(result.accepted());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("STALE_REQUIREMENT_SOURCE")));
      assertEquals(before.reference(), h.store.latestRevision(h.chat).orElseThrow().reference());
      assertEquals(newer.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
    }
  }

  @Test
  void aPendingAuthorAmendmentSurvivesAnEarlierAdditiveSave() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks at /tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var source = h.begin("Preserve request IDs and change the endpoint URL to /new-tasks.");
      var additive = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.", source.turnId(), "Preserve request IDs")), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      assertTrue(h.update(additive).accepted());
      h.store.beginTurn(h.chat, "Automatic correction.");
      var evidence = List.of(new AmendmentInput("/capabilities/entry/path", source.turnId(),
          "change the endpoint URL to /new-tasks."));
      var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/new-tasks", null, null, null)), List.of(), List.of(), List.of(), List.of(), null, evidence);
      assertTrue(h.update(update).accepted());
      var repeated = new DraftUpdate(update.addInteractions(), update.updateInteractions(), update.removeInteractionIds(),
          update.addTransitions(), update.removeTransitions(), update.addFacts(), update.updateFacts(), update.removeFactIds(),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/wrong-url", null, null, null)), List.of(), List.of(), List.of(), List.of(), null, evidence);
      assertFalse(h.update(repeated).accepted());
      assertEquals("/new-tasks", h.store.get(h.chat).orElseThrow().authoredDraft().capabilities().getFirst().path());
    }
  }

  @Test
  void nativeSourceOwnerRepairChangesOnlyTheReportedFieldAndRetainsNewFacts() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      var source = h.begin("Create one task and preserve request IDs. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create two tasks."), fact("sibling", "Preserve inputs.")))).accepted());
      var selected = h.store.get(h.chat).orElseThrow();
      var capability = new org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability(
          null, h.store, (chat, text) -> {
            try {
              assertEquals(java.util.Set.of("/facts/goal/text"),
                  org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
                      .binding(chat).orElseThrow().requirementRepairPaths());
              var prompt = new org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder(
                  null, null, h.store).wrap(chat, "Automatic correction.");
              assertTrue(prompt.contains("Writable stable paths: [/facts/goal/text]"), prompt);
              assertTrue(prompt.contains("Use amendments: []"), prompt);
              var bad = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
                  List.of(fact("retain-id", "Preserve request IDs.", source.turnId(), "request IDs")),
                  List.of(fact("goal", "Create one task."), fact("sibling", "Drop inputs.")), List.of(), List.of(),
                  List.of(), List.of(), List.of(), List.of(), null);
              assertFalse(h.update(bad).accepted());
              var repaired = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
                  List.of(fact("retain-id", "Preserve request IDs.", source.turnId(), "request IDs")), List.of(fact("goal", "Create one task.")),
                  List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
              var result = h.update(repaired);
              assertTrue(result.accepted(), result.toString());
              assertEquals(List.of("Create one task.", "Preserve inputs.", "Preserve request IDs."),
                  result.draft().facts().stream().map(FactInput::text).toList(), result.toString());
              h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            return io.smallrye.mutiny.Multi.createFrom().empty();
          }, null);
      var cause = new org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause(
          org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.BRIEF_CONTRACT,
          List.of(new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
              "SOURCE_LITERAL_MISMATCH", "The source requests one task.", true,
              org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
              "goal", "/facts[]/text")), "");
      var attrs = java.util.Map.<String, Object>of("userText", "",
          org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
          "The source requests one task.",
          org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
          "BRIEF_CONTRACT",
          org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
          h.mapper.writeValueAsString(cause));
      capability.execute(new org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext(
          "run", h.chat, "requirement-discovery", "exec", "attempt", null, null, List.of(), attrs))
          .collect().asList().await().indefinitely();
      assertEquals(List.of("Create one task.", "Preserve inputs.", "Preserve request IDs."),
          h.store.get(h.chat).orElseThrow().authoredDraft().facts().stream().map(FactInput::text).toList());
      assertNotEquals(selected, h.store.get(h.chat).orElseThrow());
    }
  }

  @Test
  void obsoleteImportCompletionCannotReplaceTheNewDraft() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var oldAuthority = h.store.writeAuthority(h.chat);
      var added = h.begin("Preserve request IDs.");
      assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.", added.turnId(), "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null)).accepted());
      var current = h.store.latestRevision(h.chat).orElseThrow();
      assertFalse(h.store.applyImportResult(h.chat, oldAuthority, "entry",
          new ResolvedCatalogBinding("old-system", "old-spec", "old-group", "old-operation", null)));
      assertEquals(current.reference(), h.store.latestRevision(h.chat).orElseThrow().reference());
    }
  }

  @Test
  void rejectedProtectionNeverReplacesAPreviouslyValidatedMapping() throws Exception {
    var h = new Harness();
    h.begin("Map the input id to TargetId and retain all facts.");
    var old = new FactInput("mapping", List.of("entry"), FactKind.FIELD_MAPPING, Polarity.POSITIVE,
        "Map the input id.", new FieldMappingInput("entry", "$.id", "entry", "$.TargetId", null));
    var changed = new FactInput(old.sourceFactId(), List.of(), old.kind(), old.polarity(), old.text(),
        new FieldMappingInput("entry", "$.other", "entry", "$.Changed", "changed expression"));
    h.store.rememberRejectedCaptureFacts(h.chat, List.of(old));
    h.store.rememberRejectedCaptureFacts(h.chat, List.of(changed));
    h.restartStore();
    h.store.beginTurn(h.chat, "Automatic correction.");
    assertEquals(old, h.store.rejectedCaptureFacts(h.chat).getFirst());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedCaptureRetainsValidMappingSiblingsAndNonfactValues(boolean recreateStore) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Map id to Subject and keep the /tasks endpoint. Proceed without IDS.");
      var initial = mappingDraft("$.Subject,$.Other", "$.id", "/tasks");
      assertFalse(h.capture(initial).accepted());
      if (recreateStore) {
        h.restartStore();
        h.store.beginTurn(h.chat, "Automatic correction.");
      }
      var moved = h.capture(mappingDraft("$.Subject", "$.wrong", "/tasks"));
      assertTrue(moved.accepted(), moved.toString());
      assertEquals("$.wrong", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().fieldMapping().sourcePath());
      var endpoint = h.capture(mappingDraft("$.Subject", "$.id", "/wrong-tasks"));
      assertFalse(endpoint.accepted(), endpoint.toString());
      assertEquals("$.wrong", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().fieldMapping().sourcePath());
      assertEquals("/tasks", h.store.get(h.chat).orElseThrow().authoredDraft().capabilities().getFirst().path());
    }
  }

  @Test
  void droppedAcceptedTransitionIsRestoredSoDiscoveryCanContinue() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks before delivery. Proceed without IDS.");
      var initial = mappingDraft("$.Subject", "$.id", "/tasks");
      initial = new DraftInput(initial.flow(), List.of(fact("goal", "Create tasks before delivery.")),
          initial.capabilities(), initial.openQuestions(), initial.settings());
      assertTrue(h.capture(initial).accepted());
      var edit = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), initial.flow().transitions(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var restored = h.update(edit);
      assertTrue(restored.accepted(), restored.toString());
      assertEquals(initial.flow().transitions(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
      assertTrue(h.update(edit).accepted());
      assertEquals(initial.flow().transitions(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
    }
  }

  @Test
  void automaticRepairCannotRewireAcceptedTransitionsButAnAuthorCan() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks before delivery. Proceed without IDS.");
      var initial = mappingDraft("$.Subject", "$.id", "/tasks");
      initial = new DraftInput(initial.flow(), List.of(fact("goal", "Create tasks before delivery.")),
          initial.capabilities(), initial.openQuestions(), initial.settings());
      assertTrue(h.capture(initial).accepted());
      var removed = initial.flow().transitions();
      var edit = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), removed, List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var kept = h.update(edit);
      assertTrue(kept.accepted(), kept.toString());
      assertEquals(initial.flow().transitions(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
      var source = h.begin("Remove the transition from entry to delivery.");
      var amendment = new DraftUpdate(edit.addInteractions(), edit.updateInteractions(), edit.removeInteractionIds(),
          edit.addTransitions(), edit.removeTransitions(), edit.addFacts(), edit.updateFacts(), edit.removeFactIds(),
          edit.setCapabilities(), edit.removeCapabilityInteractionIds(), edit.addQuestions(), edit.updateQuestions(),
          edit.removeQuestionIds(), edit.settings(), List.of(new AmendmentInput("/transitions/entry", source.turnId(),
              "Remove the transition from entry to delivery.")));
      var admitted = h.update(amendment);
      assertTrue(admitted.accepted(), admitted.toString());
      assertTrue(h.store.get(h.chat).orElseThrow().flow().transitions().isEmpty());
    }
  }

  @Test
  void derivedBindingRefreshDoesNotConsumePendingAuthorInput() throws Exception {
    var h = new Harness();
    var fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(
        h.mapper, h.artifacts, h.sessions);
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(
        fingerprints, h.artifacts, h.sessions);
    var bindings = mock(RequirementDraftTool.class);
    when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    h.tools = new RequirementCaptureTools(h.store, bindings, h.mapper,
        new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, feedback),
        feedback, null);
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var source = h.begin("Also preserve the request ID.");
      var retained = fact("retain-id", "Preserve request IDs.", source.turnId(), "request ID");
      var rejected = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(retained), List.of(fact("goal", "Drop tasks.")),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertFalse(h.update(rejected).accepted());
      h.store.put(h.chat, h.store.get(h.chat).orElseThrow().withImportIntent(true));
      assertEquals(source.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
      assertTrue(h.store.latestRevision(h.chat).orElseThrow().inputs().isEmpty());
      var protection = h.store.repairConstraints(h.chat);
      assertFalse(h.update(rejected).accepted());
      var stopped = assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
          () -> h.update(rejected), "A binding-only refresh must not grant another identical proposal");
      assertNotNull(stopped.evidence());
      assertTrue(stopped.evidence().feedback().noProgress());
      assertFalse(stopped.evidence().feedback().outerAllowed());
      assertTrue(stopped.evidence().feedback().issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
          && "/changes/facts/goal".equals(issue.path()) && "goal".equals(issue.entityId())));
      assertNotNull(stopped.evidence().feedback().evidenceReference());
      assertTrue(h.artifacts.get(h.store.activeCompilationId(h.chat), stopped.evidence().feedback().evidenceReference()).isPresent());
      assertEquals(source.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
      assertEquals(protection, h.store.repairConstraints(h.chat));
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      assertTrue(h.store.validateCurrent(h.chat).isEmpty(),
          "A rejected addition is not part of the accepted draft");
      assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(retained), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null)).accepted());
      assertTrue(h.archive.pendingInputs(h.chat).isEmpty());
    }
  }

  @Test
  void arrayIndexesCannotClaimUnimplementedAmendmentAuthority() {
    var base = mappingDraft("$.Subject", "$.id", "/tasks");
    assertFalse(RequirementRevisionAdmission.validPath(base, "/facts/map/interactionIds/0"));
    assertTrue(RequirementRevisionAdmission.validPath(base, "/facts/map/interactionIds"));
  }

  private static DraftInput mappingDraft(String target, String source, String endpoint) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", "Task endpoint", null, null), new InteractionInput("delivery", Direction.OUTBOUND,
        "Receiver", "Create task", "Deliver the task", null, null)), List.of(new TransitionInput("entry", "delivery"))),
        List.of(new FactInput("map", List.of("entry", "delivery"), FactKind.FIELD_MAPPING, Polarity.POSITIVE,
            "Map the input id to Subject.", new FieldMappingInput("entry", source, "delivery", target, null))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            endpoint, null, null, null)), List.of(), new DraftSettings(false, null));
  }

  @Test
  void acceptedBaselineCannotChangeWithoutAmendmentInput() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      var original = fact("goal", "Create tasks.");
      assertTrue(h.capture(httpDraft(List.of(original))).accepted());
      assertTrue(h.store.semanticBaseline(h.chat).isEmpty(),
          "A quote-less capture does not write a semantic baseline");
      var changed = new FactInput(original.sourceFactId(), original.interactionIds(), original.kind(),
          Polarity.NEGATIVE, "Create two tasks.");
      var refused = h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(changed), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
          && "goal".equals(issue.entityId())), refused.toString());
      assertEquals(original, h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst());
      assertTrue(h.store.semanticBaseline(h.chat).isEmpty());

      var source = h.begin("Create two tasks.");
      var amended = h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(new FactInput(original.sourceFactId(), original.interactionIds(), original.kind(),
              original.polarity(), "Create two tasks.")),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
          List.of(new AmendmentInput("/facts/goal/text", source.turnId(), "Create two tasks"))));
      assertTrue(amended.accepted(), amended.toString());
      assertEquals("Create two tasks.", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().text());
    }
  }

  @Test
  void focusedRepairWithoutAnInScopeAlternativeBecomesProjectionRebuild() {
    var finding = new RequirementFinding("COMPILER_QUALITY",
        "/consumers/quality", null, List.of(), List.of(), List.of(),
        "The native approved-value check failed.");
    var decision = RequirementRepairPolicy.evaluate(rejectedContext(), null, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.PROJECTION_REBUILD, decision.strategy());
    assertEquals(List.of(finding), decision.findings());
  }

  @Test
  void editablePathOnARejectedProjectionStaysFocusedRepair() {
    var finding = new RequirementFinding("UNKNOWN_INTERACTION_REFERENCE",
        "/draft/facts/0/interactionIds/0", "goal", List.of(), List.of(), List.of(),
        "Target must name an accepted interaction.");
    var decision = RequirementRepairPolicy.evaluate(rejectedContext(), null, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, decision.strategy());
    assertEquals(List.of("/draft/facts/0/interactionIds/0"), decision.findings().getFirst().repairAlternatives());
  }

  @Test
  void structuralPathOnARejectedProjectionStaysFocusedRepairWithoutASuppliedAlternative() {
    var finding = new RequirementFinding("INVALID_ELEMENT_OWNER",
        "/draft/facts/local/elementIntent/ownerInteractionId", "local", List.of(), List.of(), List.of(),
        "The owner is missing.");
    var decision = RequirementRepairPolicy.evaluate(rejectedContext(), null, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, decision.strategy());
    assertEquals(List.of(finding.path()), decision.findings().getFirst().repairAlternatives());
  }

  @Test
  void structuralFactLossStaysWhenClaimTextAndPolarityMatch() {
    var claim = "Create tasks.";
    var base = httpDraft(List.of(fact("goal", claim)));
    var changed = new FactInput("goal", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, claim);
    var candidate = httpDraft(List.of(changed));
    var baseline = new RequirementSemanticBaseline("generation", "hash",
        List.of(new RequirementSemanticClaim("goal", claim, Polarity.POSITIVE, null)));
    var finding = new RequirementFinding("REPAIR_FACT_LOSS", "/changes/facts/goal", "goal",
        List.of(), List.of(), List.of(), "Keep the accepted fact.");
    var context = new RequirementRepairPolicy.RequirementRepairContext(
        base, baseline, java.util.Set.of(), java.util.Set.of(), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
    var decision = RequirementRepairPolicy.evaluate(context, candidate, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.PROJECTION_REBUILD, decision.strategy());
    assertEquals(List.of(finding), decision.findings());
  }

  @Test
  void exactValueChangeOnAnAcceptedDraftStaysAFactLoss() {
    var text = "Create one task.";
    var originalIntent = new RequirementElementIntent("entry", "http-trigger",
        RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, List.of(), List.of("task"));
    var changedIntent = new RequirementElementIntent("entry", "http-trigger",
        RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, List.of(), List.of("one"));
    var original = new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, text,
        null, null, originalIntent);
    var changed = new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, text,
        null, null, changedIntent);
    var baseline = new RequirementSemanticBaseline("generation", "hash",
        List.of(new RequirementSemanticClaim("goal", text, Polarity.POSITIVE,
            new ClaimEvidenceInput("turn-1", "task"))));
    var issues = RequirementRevisionAdmission.validate(httpDraft(List.of(original)), httpDraft(List.of(changed)),
        List.of(), new ObjectMapper().createObjectNode(), java.util.Set.of(), java.util.Set.of(),
        java.util.Map.of(), fact -> false, baseline);
    assertTrue(issues.stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
        && "goal".equals(issue.entityId())), issues.toString());
  }

  @Test
  void savedBaselinePhraseIsNotOneLiteral() {
    String phrase = "exactly audit-event";
    String sentence = "Log exactly audit-event with log level Info.";
    var quoteClaim = new RequirementSemanticClaim("audit", sentence, Polarity.POSITIVE,
        new ClaimEvidenceInput("turn-1", phrase));
    var textClaim = new RequirementSemanticClaim("audit", sentence, Polarity.POSITIVE, null);
    var candidate = absentLiteral();

    assertFalse(unboundLiteral(admit(List.of(), candidate, baseline(quoteClaim)), phrase),
        "A spaced quote is claim evidence, not one literal");
    assertFalse(unboundLiteral(admit(List.of(), candidate, baseline(textClaim)), sentence),
        "A spaced claim text is not one literal");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST /sobjects/Task", "GET /health"})
  void spacedHttpOperationIsARequiredLiteral(String operation) {
    var quoteClaim = new RequirementSemanticClaim("audit", "Call the operation.",
        Polarity.POSITIVE, new ClaimEvidenceInput("turn-1", operation));
    var textClaim = new RequirementSemanticClaim("audit", operation, Polarity.POSITIVE, null);
    String turn = "Use " + operation + " now.";
    var uncovered = absentLiteral();
    var missingQuote = replaceFact(uncovered, quoted(uncovered.facts().getFirst(), "turn-1", operation));
    var coveredProperty = coveredValue("property", operation);
    var boundQuote = replaceFact(coveredProperty, quoted(coveredProperty.facts().getFirst(), "turn-1", operation));

    assertAll(
        () -> assertEquals(List.of(operation),
            unboundTokens(admit(List.of(), uncovered, baseline(quoteClaim))), "quote"),
        () -> assertEquals(List.of(operation),
            unboundTokens(admit(List.of(), uncovered, baseline(textClaim))), "claim text"),
        () -> {
          for (String site : List.of("name", "property", "body", "mapping")) {
            assertEquals(List.of(), unboundTokens(admit(List.of(), coveredValue(site, operation),
                baseline(quoteClaim))), site);
          }
        },
        () -> assertEquals(List.of(operation),
            unboundTokens(quoteDecision(missingQuote, "turn-1", turn)), "candidate quote"),
        () -> assertEquals(List.of(),
            unboundTokens(quoteDecision(boundQuote, "turn-1", turn)), "covered candidate quote"));
  }

  @ParameterizedTest
  @ValueSource(strings = {".", ","})
  void quotedHttpOperationDropsOneTrailingPunctuation(String mark) {
    String operation = "POST /sobjects/Task";
    String quote = "Call createTask with " + operation + mark + " now";
    var claim = new RequirementSemanticClaim("audit", "Call the operation.",
        Polarity.POSITIVE, new ClaimEvidenceInput("turn-1", quote));
    assertAll(
        () -> assertEquals(List.of(operation),
            unboundTokens(admit(List.of(), absentLiteral(), baseline(claim))), "quote"),
        () -> assertEquals(List.of(),
            unboundTokens(admit(List.of(), coveredValue("property", operation), baseline(claim))),
            "property without punctuation"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"audit-event", "trace-id"})
  void sentenceQuoteRequiresOnlyTheHyphenatedToken(String token) {
    String sentence = "log exactly " + token + " before Friday";
    var quoteClaim = new RequirementSemanticClaim("audit", "Log the audit event.",
        Polarity.POSITIVE, new ClaimEvidenceInput("turn-1", sentence));
    var textClaim = new RequirementSemanticClaim("audit", sentence, Polarity.POSITIVE, null);
    var ordinary = new RequirementSemanticClaim("audit", "log exactly before Friday",
        Polarity.POSITIVE, null);
    String turn = "Please " + sentence + ".";
    var uncovered = absentLiteral();
    var missingQuote = replaceFact(uncovered, quoted(uncovered.facts().getFirst(), "turn-1", sentence));
    var covered = coveredValue("property", token);
    var boundQuote = replaceFact(covered, quoted(covered.facts().getFirst(), "turn-1", sentence));

    assertAll(
        () -> assertEquals(List.of(token),
            unboundTokens(admit(List.of(), uncovered, baseline(quoteClaim))), "quote"),
        () -> assertEquals(List.of(token),
            unboundTokens(admit(List.of(), uncovered, baseline(textClaim))), "claim text"),
        () -> assertEquals(List.of(),
            unboundTokens(admit(List.of(), uncovered, baseline(ordinary))), "ordinary words"),
        () -> assertEquals(List.of(),
            unboundTokens(admit(List.of(), covered, baseline(quoteClaim))), "message property"),
        () -> assertEquals(List.of(token),
            unboundTokens(quoteDecision(missingQuote, "turn-1", turn)), "candidate quote"),
        () -> assertEquals(List.of(),
            unboundTokens(quoteDecision(boundQuote, "turn-1", turn)), "covered candidate quote"));
  }

  @Test
  void rejectedSnapshotTransitionsAreNotRestored() {
    var accepted = new TransitionInput("entry", "delivery");
    var base = withTransitions(httpDraft(List.of(fact("goal", "Create tasks."))), List.of(accepted));
    var candidate = withTransitions(base, List.of());
    var rejected = new ObjectMapper().createObjectNode();
    rejected.putObject("transitions").putArray("delivery").add("audit");
    var issues = List.of(new RequirementCaptureEditor.Issue("AUTHOR_AMENDMENT_REQUIRED", "/transitions/entry",
        "entry", "Preserve this accepted transition or cite a source-linked amendment for its outgoing transitions."));

    var restored = RequirementRevisionAdmission.restoreProtectedSnapshot(base, candidate, rejected, List.of(), issues);

    assertNotNull(restored);
    assertEquals(List.of(accepted), restored.flow().transitions());
  }

  @Test
  void rejectedExactValueIsRequiredOnlyWhenTheBaselineGroundsIt() {
    var invented = "model-only-token";
    var fromQuote = "audit-event";
    var fromText = "route-key";
    var rejected = List.of(literalIntent(List.of(invented)));
    var quoteClaim = new RequirementSemanticClaim("audit", "Log exactly audit-event",
        Polarity.POSITIVE, new ClaimEvidenceInput("turn-1", fromQuote));
    var textClaim = new RequirementSemanticClaim("route", fromText, Polarity.POSITIVE, null);
    var separateQuote = new RequirementSemanticClaim("batch", "Record the batch",
        Polarity.POSITIVE, new ClaimEvidenceInput("turn-1", fromQuote));

    assertAll(
        () -> assertFalse(unboundLiteral(admit(rejected, absentLiteral(), null), invented),
            "A rejected exactValues entry is not an obligation"),
        () -> assertTrue(unboundLiteral(admit(rejected, absentLiteral(), baseline(quoteClaim)), fromQuote)),
        () -> assertFalse(unboundLiteral(admit(rejected, absentLiteral(), baseline(quoteClaim)),
            "Log exactly audit-event")),
        () -> assertTrue(unboundLiteral(admit(List.of(), absentRoute(), baseline(textClaim)), fromText)),
        () -> assertTrue(unboundLiteral(admit(rejected, absentLiteral(), baseline(separateQuote)), fromQuote)),
        () -> {
          for (String site : List.of("name", "property", "body", "mapping")) {
            assertFalse(unboundLiteral(admit(rejected, coveredLiteral(site), baseline(quoteClaim)), fromQuote), site);
            assertFalse(unboundLiteral(admit(rejected, coveredLiteral(site), baseline(quoteClaim)), invented), site);
          }
        });
  }

  @Test
  void structuralAlternativeOnARejectedProjectionStaysFocusedRepair() {
    var finding = new RequirementFinding("INVALID_ELEMENT_OWNER",
        "/draft/facts/local/elementIntent/ownerInteractionId", "local", List.of(), List.of(),
        List.of("/draft/facts/local/elementIntent/ownerInteractionId"), "The owner is missing.");
    var decision = RequirementRepairPolicy.evaluate(rejectedContext(), null, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, decision.strategy());
  }

  @Test
  void acceptedDraftIgnoresAStructuralAlternativeOutsideAuthorAndServerPaths() {
    var finding = new RequirementFinding("REPAIR_FACT_LOSS", "/changes/facts/goal/elementIntent/elementRef",
        "goal", List.of(), List.of(), List.of("/changes/facts/goal/elementIntent/elementRef"),
        "Keep the accepted fact.");
    var accepted = httpDraft(List.of(fact("goal", "Create tasks.")));
    var context = new RequirementRepairPolicy.RequirementRepairContext(
        accepted, null, java.util.Set.of(), java.util.Set.of(), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
    var decision = RequirementRepairPolicy.evaluate(context, accepted, List.of(finding));
    assertEquals(RequirementRepairPolicy.Strategy.PROJECTION_REBUILD, decision.strategy());
    assertEquals(List.of(finding), decision.findings());
  }

  private static RequirementRepairPolicy.RequirementRepairContext rejectedContext() {
    return new RequirementRepairPolicy.RequirementRepairContext(
        null, null, java.util.Set.of(), java.util.Set.of(), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
  }

  @Test
  void unchangedUpdatesAndContinuationCannotDropRejectedValidContent() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      var opened = h.begin("Create tasks and retain request IDs. Proceed without IDS.");
      assertTrue(h.capture(withNativeAnchors(httpDraft(List.of(fact("goal", "Create tasks."))))).accepted());
      var retained = fact("retain-id", "Preserve request IDs.", opened.turnId(), "request IDs");
      var damaged = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(retained), List.of(fact("goal", "Drop tasks.")),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertFalse(h.update(damaged).accepted());
      var noop = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertTrue(h.update(noop).accepted(), "A rejected addition is not an obligation of the accepted draft");
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      var correction = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(retained), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      assertTrue(h.update(correction).accepted());
      assertTrue(h.store.validateCurrent(h.chat).isEmpty());
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
    }
  }

  @Test
  void invalidReferencesReleaseOnlyTheReferenceAndRetainTheFactText() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Preserve request IDs. Proceed without IDS.");
      var damaged = new FactInput("retain-id", List.of("missing"), FactKind.GOAL, Polarity.POSITIVE,
          "Preserve request IDs.");
      assertFalse(h.capture(httpDraft(List.of(damaged))).accepted());
      var changed = h.capture(httpDraft(List.of(new FactInput("retain-id", List.of("entry"), FactKind.GOAL,
          Polarity.POSITIVE, "Drop request IDs."))));
      assertTrue(changed.accepted(), changed.toString());
      assertEquals("Drop request IDs.", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().text());
    }
  }

  @Test
  void duplicateTransitionRepairRetainsTheValidEdge() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks and then deliver them. Proceed without IDS.");
      var initial = mappingDraft("$.Subject", "$.id", "/tasks");
      var edge = initial.flow().transitions().getFirst();
      var duplicated = new DraftInput(new FlowInput(initial.flow().interactions(), List.of(edge, edge)),
          List.of(fact("goal", "Create tasks and deliver them.")), initial.capabilities(), List.of(), initial.settings());
      assertFalse(h.capture(duplicated).accepted());
      var lost = new DraftInput(new FlowInput(initial.flow().interactions(), List.of()), duplicated.facts(),
          initial.capabilities(), List.of(), initial.settings());
      var retained = h.capture(lost);
      assertTrue(retained.accepted(), retained.toString());
      assertEquals(List.of(), h.store.get(h.chat).orElseThrow().authoredDraft().flow().transitions());
    }
  }

  private static List<RequirementCaptureEditor.Issue> admit(List<FactInput> protectedFacts, DraftInput candidate,
      RequirementSemanticBaseline baseline) {
    return RequirementRevisionAdmission.validate(null, candidate, protectedFacts,
        new ObjectMapper().createObjectNode(), java.util.Set.of(), java.util.Set.of(),
        java.util.Map.of(), fact -> false, baseline);
  }

  private static boolean unboundLiteral(List<RequirementCaptureEditor.Issue> issues, String literal) {
    String token = "[\"" + literal + "\"]";
    return issues.stream().anyMatch(issue -> "UNBOUND_REQUIRED_LITERAL".equals(issue.code())
        && issue.message() != null && issue.message().contains(token));
  }

  private static List<String> unboundTokens(List<RequirementCaptureEditor.Issue> issues) {
    return quotedLiterals(issues.stream()
        .filter(issue -> "UNBOUND_REQUIRED_LITERAL".equals(issue.code()))
        .map(RequirementCaptureEditor.Issue::message).toList());
  }

  private static List<String> unboundTokens(RequirementRepairPolicy.RequirementRepairDecision decision) {
    return quotedLiterals(decision.findings().stream()
        .filter(finding -> "UNBOUND_REQUIRED_LITERAL".equals(finding.code()))
        .map(RequirementFinding::message).toList());
  }

  private static List<String> quotedLiterals(List<String> messages) {
    var tokens = new ArrayList<String>();
    var pattern = Pattern.compile("\\[\"([^\"]*)\"\\]");
    for (String message : messages) {
      if (message == null) {
        continue;
      }
      var matcher = pattern.matcher(message);
      if (matcher.find()) {
        tokens.add(matcher.group(1));
      }
    }
    return tokens;
  }

  private static RequirementRepairPolicy.RequirementRepairDecision quoteDecision(
      DraftInput candidate, String turnId, String turn) {
    var source = new AuthorSourceEvidence.Packet("generation",
        List.of(new AuthorSourceEvidence.Input(turnId, turn)));
    var context = new RequirementRepairPolicy.RequirementRepairContext(
        null, null, java.util.Set.of(), java.util.Set.of(),
        RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, source);
    return RequirementRepairPolicy.evaluate(context, candidate, List.of());
  }

  private static DraftInput replaceFact(DraftInput draft, FactInput fact) {
    return new DraftInput(draft.flow(), List.of(fact), draft.capabilities(), draft.openQuestions(),
        draft.settings());
  }

  private static RequirementSemanticBaseline baseline(RequirementSemanticClaim claim) {
    return new RequirementSemanticBaseline("generation", "hash", List.of(claim));
  }

  private static FactInput literalIntent(List<String> exactValues) {
    return new FactInput("audit", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Log exactly audit-event",
        null, null, new RequirementElementIntent("audit", "log-record",
            RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, null, List.of(), exactValues));
  }

  private static DraftInput absentLiteral() {
    return literalDraft(null, List.of(), null);
  }

  private static DraftInput absentRoute() {
    return new DraftInput(absentLiteral().flow(), List.of(new FactInput("route", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "route-key", null, null, new RequirementElementIntent("route", "log-record",
            RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, null, List.of(), List.of()))),
        absentLiteral().capabilities(), List.of(), absentLiteral().settings());
  }

  private static DraftInput coveredLiteral(String site) {
    return coveredValue(site, "audit-event");
  }

  private static DraftInput coveredValue(String site, String literal) {
    String requestedName = "name".equals(site) ? literal : null;
    List<PlanProperty> properties = switch (site) {
      case "property" -> List.of(new PlanProperty("message", literal));
      case "body" -> List.of(new PlanProperty("script", "def saved = \"" + literal + "\"\n"));
      default -> List.of();
    };
    String expression = "mapping".equals(site) ? literal : null;
    return literalDraft(requestedName, properties, expression);
  }

  private static DraftInput literalDraft(String requestedName, List<PlanProperty> properties, String expression) {
    var flow = new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", "Task endpoint", null, null),
        new InteractionInput("delivery", Direction.OUTBOUND, "Receiver", "Create task", null, null, null)),
        List.of(new TransitionInput("entry", "delivery")));
    var intent = new RequirementElementIntent("audit", "log-record",
        RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, requestedName, properties, List.of());
    var fact = new FactInput("audit", List.of("entry", "delivery"), FactKind.FIELD_MAPPING, Polarity.POSITIVE,
        "Log exactly audit-event", new FieldMappingInput("entry", "$.id", "delivery", "$.Subject", expression),
        null, intent);
    return new DraftInput(flow, List.of(fact), List.of(new CapabilityInput("entry", "http-trigger",
        HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static FactInput fact(String id, String text) {
    return new FactInput(id, List.of(), FactKind.GOAL, Polarity.POSITIVE, text);
  }

  private static FactInput fact(String id, String text, String turnId, String quote) {
    return new FactInput(id, List.of(), FactKind.GOAL, Polarity.POSITIVE, text, null, null, null,
        new ClaimEvidenceInput(turnId, quote));
  }

  private static FactInput quoted(FactInput fact, String turnId, String quote) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
        fact.fieldMapping(), fact.policyConflict(), fact.elementIntent(), new ClaimEvidenceInput(turnId, quote));
  }

  private static DraftInput withTransitions(DraftInput draft, List<TransitionInput> transitions) {
    return new DraftInput(new FlowInput(draft.flow().interactions(), transitions), draft.facts(),
        draft.capabilities(), draft.openQuestions(), draft.settings());
  }

  private static DraftInput httpDraft(List<FactInput> facts) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", "Task endpoint", null, null)), List.of()), facts,
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/tasks", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static DraftInput parallelHttpDraft(String targetId) {
    var base = httpDraft(List.of(fact("goal", "Send tasks to Task API and Audit API.")));
    return withNativeAnchors(new DraftInput(new FlowInput(List.of(base.flow().interactions().getFirst(),
        new InteractionInput(targetId, Direction.OUTBOUND, "Task API", "Create task", null, null, null),
        new InteractionInput("audit", Direction.OUTBOUND, "Audit API", "Record task", null, null, null)),
        List.of(new TransitionInput("entry", targetId), new TransitionInput("entry", "audit"))), base.facts(),
        List.of(base.capabilities().getFirst(),
            new CapabilityInput(targetId, "http-sender", null, HttpMethod.POST,
                "https://example.com/tasks", null, null, null),
            new CapabilityInput("audit", "http-sender", null, HttpMethod.POST,
                "https://example.com/audit", null, null, null)), List.of(), base.settings()));
  }

  private static FactInput policy(Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "External access control: requested NONE; company policy recommends RBAC.", null,
        new RequirementPolicyConflict("security", "accessControlType", "NONE", "RBAC", "choice", decision));
  }

  private static QuestionInput question() {
    return new QuestionInput("choice", List.of("entry"), "Keep NONE or use the recommended RBAC?");
  }

  private static DraftInput policyDraft(Decision decision) {
    var base = httpDraft(List.of(fact("goal", "Create tasks."), policy(decision)));
    return new DraftInput(base.flow(), base.facts(), base.capabilities(),
        decision == null ? List.of(question()) : List.of(), base.settings());
  }

  private static DraftUpdate editPolicy(Decision decision, boolean reopen) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(policy(decision)),
        List.of(), List.of(), List.of(), reopen ? List.of(question()) : List.of(), List.of(),
        reopen ? List.of() : List.of("choice"), null);
  }

  private static final class Harness {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ConversationArchive archive = new ConversationArchive(blobs, mapper);
    final ConversationService conversations = mock(ConversationService.class);
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    final String chat = UUID.randomUUID().toString();
    RequirementDraftStore store;
    RequirementCaptureTools tools;
    RequirementDraftTool bindings;

    Harness() {
      when(conversations.archive()).thenReturn(archive);
      restartStore();
    }

    void restartStore() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      bindings = mock(RequirementDraftTool.class);
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      tools = new RequirementCaptureTools(store, bindings, mapper);
    }

    void resolveDelivery() throws Exception {
      var binding = new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint(
          "3", "delivery", "delivery", "Deliver task", "delivery-api", "delivery-group", "delivery-spec",
          "delivery-operation", "http", "POST", "/deliveries", "2024.4", java.time.Instant.EPOCH, "catalog-evidence");
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of(binding));
      assertTrue(result(tools.readRequirementDraft()).accepted());
    }

    ConversationArchive.Input begin(String text) {
      var input = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, input, text);
      store.beginTurn(chat, text);
      return input;
    }

    ConversationArchive.Input select(Decision decision) {
      var choices = new PolicyChoiceSelection(artifacts, sessions, conversations);
      var offered = choices.pending(chat, 7).orElseThrow();
      var command = new ChatDecisionCommand();
      command.setAction(decision == Decision.KEEP_REQUESTED ? ChatEvent.KEEP_REQUESTED_ACTION : ChatEvent.USE_RECOMMENDED_ACTION);
      command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
      command.setArtifactHash(offered.hash());
      command.setRevision(7);
      String marker = ChatDecisionService.transcriptMarker(command);
      var source = archive.beginInput(chat, marker, mapper.valueToTree(command));
      archive.resolveInput(chat, source, marker, mapper.valueToTree(command));
      store.beginTurn(chat, marker);
      assertTrue(choices.confirm(chat, command, 7).isPresent());
      return source;
    }

    RequirementCaptureResult result(String json) throws Exception {
      return mapper.readValue(json, RequirementCaptureResult.class);
    }

    RequirementCaptureResult capture(DraftInput draft) throws Exception {
      return result(tools.captureRequirementDraft(draft));
    }

    RequirementCaptureResult update(DraftUpdate update) throws Exception {
      return result(tools.updateRequirementDraft(update));
    }
  }
}
