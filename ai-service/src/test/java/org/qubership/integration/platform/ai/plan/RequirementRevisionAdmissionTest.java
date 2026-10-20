package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementRevisionAdmissionTest {

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
        var base = new DraftInput(initial.flow(), initial.facts(), initial.capabilities(), List.of(),
            new DraftSettings(false, null, "Tasks"));
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
      h.begin("Keep NONE despite the RBAC recommendation.");
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
  void allRejectedCandidatesContributeProtectionAcrossGatherInvocations() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks, preserve request IDs, and keep inputs. Proceed without IDS.");
      assertFalse(h.capture(httpDraft(List.of(fact("invalid id", "Create tasks.")))).accepted());
      var second = httpDraft(List.of(fact("goal", "Create tasks."), fact("retain-id", "Preserve request IDs."),
          new FactInput("invalid-ref", List.of("missing"), FactKind.GOAL, Polarity.POSITIVE, "Keep inputs.")));
      assertFalse(h.capture(second).accepted());
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      assertFalse(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      assertTrue(h.store.get(h.chat).isEmpty());
      assertTrue(h.capture(httpDraft(List.of(second.facts().get(0), second.facts().get(1),
          fact("invalid-ref", "Keep inputs.")))).accepted());
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      assertEquals(List.of("goal", "retain-id", "invalid-ref"), h.store.get(h.chat).orElseThrow()
          .authoredDraft().facts().stream().map(f -> f.sourceFactId()).toList());
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
      h.begin("Use the recommended RBAC.");
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
      h.begin("Keep NONE despite the RBAC recommendation.");
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
      var changed = h.update(amendment);
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
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
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
      h.begin("Create one task and preserve request IDs. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create two tasks."), fact("sibling", "Preserve inputs.")))).accepted());
      var selected = h.store.get(h.chat).orElseThrow();
      var capability = new org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability(
          null, h.store, (chat, text) -> {
            try {
              assertEquals(java.util.Set.of("/facts/goal/text"),
                  org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
                      .binding(chat).orElseThrow().requirementRepairPaths());
              var bad = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
                  List.of(fact("retain-id", "Preserve request IDs.")),
                  List.of(fact("goal", "Create one task."), fact("sibling", "Drop inputs.")), List.of(), List.of(),
                  List.of(), List.of(), List.of(), List.of(), null);
              assertFalse(h.update(bad).accepted());
              var lost = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                  List.of(fact("goal", "Create one task.")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
              assertFalse(h.update(lost).accepted());
              var repaired = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
                  List.of(fact("retain-id", "Preserve request IDs.")), List.of(fact("goal", "Create one task.")),
                  List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
              var result = h.update(repaired);
              assertTrue(result.accepted(), result.toString());
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
      h.begin("Preserve request IDs.");
      assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
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
      assertFalse(h.capture(mappingDraft("$.Subject", "$.wrong", "/tasks")).accepted());
      assertFalse(h.capture(mappingDraft("$.Subject", "$.id", "/wrong-tasks")).accepted());
      var repaired = h.capture(mappingDraft("$.Subject", "$.id", "/tasks"));
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals("$.id", h.store.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().fieldMapping().sourcePath());
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
      assertFalse(h.update(edit).accepted());
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
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var source = h.begin("Also preserve the request ID.");
      var rejected = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(fact("goal", "Drop tasks.")),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertFalse(h.update(rejected).accepted());
      h.store.put(h.chat, h.store.get(h.chat).orElseThrow().withImportIntent(true));
      assertEquals(source.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
      assertTrue(h.store.latestRevision(h.chat).orElseThrow().inputs().isEmpty());
      assertTrue(h.update(rejected).issues().stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())),
          "A binding-only refresh must not grant another identical proposal");
      h.restartStore();
      h.store.beginTurn(h.chat, "Automatic correction.");
      assertFalse(h.store.validateCurrent(h.chat).isEmpty(), "Binding refresh must retain verified pending additions");
      assertTrue(h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
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
  void unchangedUpdatesAndContinuationCannotDropRejectedValidContent() throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      h.begin("Create tasks and retain request IDs. Proceed without IDS.");
      assertTrue(h.capture(httpDraft(List.of(fact("goal", "Create tasks.")))).accepted());
      var damaged = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(fact("goal", "Drop tasks.")),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertFalse(h.update(damaged).accepted());
      var noop = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      assertFalse(h.update(noop).accepted());
      assertFalse(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      assertFalse(h.store.validateCurrent(h.chat).isEmpty());
      var correction = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(fact("retain-id", "Preserve request IDs.")), List.of(), List.of(), List.of(), List.of(),
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
      assertFalse(h.capture(httpDraft(List.of(new FactInput("retain-id", List.of("entry"), FactKind.GOAL,
          Polarity.POSITIVE, "Drop request IDs.")))).accepted());
      assertTrue(h.capture(httpDraft(List.of(new FactInput("retain-id", List.of("entry"), FactKind.GOAL,
          Polarity.POSITIVE, damaged.text())))).accepted());
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
      assertFalse(h.capture(lost).accepted());
      var corrected = new DraftInput(new FlowInput(initial.flow().interactions(), List.of(edge)), duplicated.facts(),
          initial.capabilities(), List.of(), initial.settings());
      assertTrue(h.capture(corrected).accepted());
    }
  }

  private static FactInput fact(String id, String text) {
    return new FactInput(id, List.of(), FactKind.GOAL, Polarity.POSITIVE, text);
  }

  private static DraftInput httpDraft(List<FactInput> facts) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", "Task endpoint", null, null)), List.of()), facts,
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/tasks", null, null, null)), List.of(), new DraftSettings(false, null));
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
    final String chat = UUID.randomUUID().toString();
    RequirementDraftStore store;
    RequirementCaptureTools tools;

    Harness() {
      when(conversations.archive()).thenReturn(archive);
      restartStore();
    }

    void restartStore() {
      store = new RequirementDraftStore(new CompilationArtifacts(blobs, mapper, Clock.systemUTC()),
          new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations);
      var bindings = mock(RequirementDraftTool.class);
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      tools = new RequirementCaptureTools(store, bindings, mapper);
    }

    ConversationArchive.Input begin(String text) {
      var input = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, input, text);
      store.beginTurn(chat, text);
      return input;
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
