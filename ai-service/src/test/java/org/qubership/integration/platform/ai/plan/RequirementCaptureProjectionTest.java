package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.RetryInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureProjectionTest {

  @Test
  void inlineResponseMayContinueIntoAnAuthoredLocalNode() {
    for (String placement : List.of("after", "anchor-link", "before", "other-owner", "negative", "absent", "self")) {
      var intent = new RequirementElementIntent(placement.equals("self") ? "call" : "reply", "script",
          RequirementElementIntent.Placement.LOCAL_NODE,
          placement.equals("other-owner") ? "entry" : "call", placement.equals("before") ? "call" : null,
          placement.equals("anchor-link") ? null : placement.equals("before") ? "entry" : "call",
          "Render response", List.of(), List.of());
      var local = new FactInput("reply-behavior", List.of("call"), FactKind.BEHAVIOR,
          placement.equals("negative") ? Polarity.NEGATIVE : Polarity.POSITIVE,
          "Render the call's success or failure in the local response script.", null, null, intent);
      var facts = new java.util.ArrayList<FactInput>();
      if (!placement.equals("absent")) facts.add(local);
      if (placement.equals("anchor-link")) facts.add(new FactInput("call-anchor", List.of("call"),
          FactKind.CONSTRAINT, Polarity.POSITIVE, "Continue into the response script.", null, null,
          new RequirementElementIntent("call", "service-call", RequirementElementIntent.Placement.ANCHOR,
              "call", "reply", null, "Remote call", List.of(), List.of())));
      var input = new DraftInput(new FlowInput(List.of(
          new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null),
          new InteractionInput("call", Direction.OUTBOUND, "Remote", "create", null,
              org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.INLINE_RESPONSE, null)),
          List.of(new TransitionInput("entry", "call"))),
          facts, List.of(), List.of(), new DraftSettings(false, null));
      boolean after = placement.equals("after") || placement.equals("anchor-link");
      assertEquals(!after, RequirementCaptureProjection.pending(input, List.of()).stream()
          .anyMatch(work -> work.code().equals("FAILURE_RESPONSE_REQUIRED")), placement);
      assertEquals(!after, RequirementFlowValidator.validateBindings(RequirementCaptureProjection.flow(input),
          RequirementCaptureProjection.facts(input), List.of())
          .filter(message -> message.contains("no successor response interaction")).isPresent(), placement);
    }
  }

  @Test
  void rbacNeedsAnExplicitRoleBeforePlanning() {
    for (String roles : List.of("", "[]", "[\"\"]", "[\"*\"]", "[\"qip-viewer\"]")) {
      var properties = new java.util.ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("accessControlType", "RBAC"));
      if (!roles.isEmpty()) {
        properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", roles));
      }
      var intent = new RequirementElementIntent("entry", "http-trigger",
          RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, properties, List.of());
      var fact = new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Use RBAC for the entry.", null, null, intent);
      var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "GET /check", null, null, null)), List.of()), List.of(fact,
              new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check the session.")),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
              "/check", null, null, null)), List.of(), new DraftSettings(false, null));

      var draft = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
      boolean supplied = roles.equals("[\"qip-viewer\"]");

      assertEquals(supplied, draft.readyForPlan(), roles);
      assertEquals(supplied, draft.openQuestions().isEmpty(), roles);
      assertEquals(input, draft.authoredDraft());
      if (!supplied) {
        var question = RequirementCaptureProjection.pending(input, List.of()).stream()
            .filter(item -> "RBAC_ROLES_REQUIRED".equals(item.code())).findFirst().orElseThrow();
        assertEquals("entry", question.interactionId());
        assertTrue(question.message().contains("Which explicit roles"));
      }
    }
  }

  @Test
  void aConfirmedNoneChoiceDoesNotRequireRbacRoles() {
    for (var decision : RequirementPolicyConflict.Decision.values()) {
      var policy = new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType",
          "NONE", "RBAC", "access-policy", decision);
      var fact = new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Retain the confirmed access policy.", null, policy);
      var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "GET /check", null, null, null)), List.of()), List.of(fact,
              new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check the session.")),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
              "/check", null, null, null)), List.of(), new DraftSettings(false, null));
      var draft = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
      assertEquals(decision == RequirementPolicyConflict.Decision.KEEP_REQUESTED, draft.readyForPlan());
      assertEquals(decision == RequirementPolicyConflict.Decision.KEEP_REQUESTED, draft.openQuestions().isEmpty());
      assertEquals(policy, draft.authoredDraft().facts().getFirst().policyConflict());
    }
  }

  @Test
  void oneTriggersRolesDoNotSupplyAnotherTriggersChoice() {
    var facts = new java.util.ArrayList<FactInput>();
    var entries = new java.util.ArrayList<InteractionInput>();
    var capabilities = new java.util.ArrayList<CapabilityInput>();
    for (String id : List.of("entry-a", "entry-b")) {
      var properties = new java.util.ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("accessControlType", "RBAC"));
      if (id.equals("entry-b")) {
        properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", "[\"qip-viewer\"]"));
      }
      facts.add(new FactInput(id + "-access", List.of(id), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Use the specified RBAC settings.", null, null, new RequirementElementIntent(id, "http-trigger",
              RequirementElementIntent.Placement.ANCHOR, id, null, null, null, properties, List.of())));
      entries.add(new InteractionInput(id, Direction.INBOUND, "Caller", "GET /" + id, null, null, null));
      capabilities.add(new CapabilityInput(id, "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
          "/" + id, null, null, null));
    }
    var input = new DraftInput(new FlowInput(entries, List.of()), facts, capabilities, List.of(), new DraftSettings(false, null));
    assertEquals(List.of("entry-a"), RequirementCaptureProjection.pending(input, List.of()).stream()
        .filter(item -> "RBAC_ROLES_REQUIRED".equals(item.code())).map(RequirementCaptureProjection.PendingWork::interactionId).toList());
  }

  @Test
  void directHttpProjectionUsesCurrentTransportValuesWithoutRewritingAuthoredDescriptions() {
    for (String capabilityKey : List.of("http-trigger", "http-sender")) {
      String path = capabilityKey.equals("http-trigger") ? "/new-tasks" : "https://example.com/new-tasks";
      var description = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
          Polarity.POSITIVE, "External endpoint at /tasks.");
      var owner = new InteractionInput("entry", capabilityKey.equals("http-trigger")
          ? Direction.INBOUND : Direction.OUTBOUND, "Caller", "POST /tasks", "Old endpoint /tasks", null, null);
      var transport = new CapabilityInput("entry", capabilityKey,
              capabilityKey.equals("http-trigger") ? HttpMode.CUSTOM : null, HttpMethod.POST,
              path, null, null, null);
      boolean sender = capabilityKey.equals("http-sender");
      DraftInput input = new DraftInput(new FlowInput(sender
          ? List.of(new InteractionInput("caller", Direction.INBOUND, "Client", "POST /start", null, null, null), owner)
          : List.of(owner), sender ? List.of(new TransitionInput("caller", "entry")) : List.of()), List.of(description),
          sender ? List.of(new CapabilityInput("caller", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/start", null, null, null), transport) : List.of(transport), List.of(), new DraftSettings(false, null));
      assertTrue(RequirementCaptureEditor.initialize(input).accepted());
      var facts = RequirementCaptureProjection.facts(input);
      assertEquals("POST " + path, facts.stream().filter(f -> f.kind() == RequirementFactKind.CAPABILITY
              && f.interactionIds().contains("entry"))
          .findFirst().orElseThrow().operation());
      assertEquals(description.text(), facts.getFirst().text());
      assertEquals("POST /tasks", owner.operation());
    }
    DraftInput catalog = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Task API", "createTask", null, null, null)), List.of()), List.of(),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CATALOG, null, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
    assertTrue(RequirementCaptureEditor.initialize(catalog).accepted());
    assertEquals("createTask", RequirementCaptureProjection.facts(catalog).getFirst().operation());
  }

  @Test
  void elementIntentSurvivesProjectionAndAutomaticRepairCannotDropIt() throws Exception {
    var intent = new RequirementElementIntent("checkpoint", "checkpoint",
        RequirementElementIntent.Placement.LOCAL_NODE, "entry", "check-properties", "entry", null,
        List.of(), List.of());
    var script = new RequirementElementIntent("check-properties", "script",
        RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, "checkpoint", "Check properties",
        List.of(), List.of("This is parent session"));
    var fact = new FactInput("checkpoint-config", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Create an internal checkpoint with a POST retry route.", null, null, intent);
    DraftInput input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "GET /check", null, null, null)), List.of()),
        List.of(fact, new FactInput("check-session", List.of("entry"), FactKind.BEHAVIOR,
            Polarity.POSITIVE, "Check properties: throw This is parent session for a missing parent ID.",
            null, null, script)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
            "/check", null, null, null)), List.of(), new DraftSettings(null, null));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    DraftInput restored = mapper.readValue(mapper.writeValueAsString(input), DraftInput.class);
    assertTrue(RequirementCaptureEditor.initialize(restored).accepted());
    assertEquals(intent, RequirementCaptureProjection.facts(restored).getFirst().elementIntent());
    assertEquals(intent, RequirementCaptureProjection.facts(restored).getFirst()
        .withKind(RequirementFactKind.CONSTRAINT).elementIntent());
    var legacyIntent = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(script);
    legacyIntent.set("beforeElementRef", legacyIntent.remove("successorElementRef"));
    legacyIntent.set("afterElementRef", legacyIntent.remove("predecessorElementRef"));
    assertEquals(script, mapper.treeToValue(legacyIntent, RequirementElementIntent.class));

    var lost = new DraftInput(input.flow(), List.of(new FactInput(fact.sourceFactId(), fact.interactionIds(),
        fact.kind(), fact.polarity(), fact.text()), input.facts().get(1)),
        input.capabilities(), input.openQuestions(), input.settings());
    assertTrue(RequirementCaptureEditor.validateFactPreservation(input, lost).stream()
        .anyMatch(issue -> issue.code().equals("REPAIR_FACT_LOSS")));
    var old = mapper.valueToTree(fact);
    ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove("elementIntent");
    assertEquals(null, mapper.treeToValue(old, FactInput.class).elementIntent());
  }

  @Test
  void relativeHelperRouteRequiresRuntimeEndpointBeforePlanning() {
    for (var example : Map.of(
        "auto-tests/get-context-headers", false,
        "/auto-tests/get-context-headers", false,
        "http://engine:8080/routes/auto-tests/get-context-headers", true,
        "https://runtime.example/routes/${header.helperPath}", true).entrySet()) {
      DraftInput input = new DraftInput(
          new FlowInput(List.of(
              new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /check",
                  null, null, null),
              new InteractionInput("helper", Direction.OUTBOUND, "Get Context Headers", "GET",
                  null, null, null)), List.of(new TransitionInput("entry", "helper"))),
          List.of(new FactInput("helper-behavior", List.of("helper"), FactKind.BEHAVIOR,
              Polarity.POSITIVE, "Call the helper and verify its context headers.")),
          List.of(
              new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
                  "/check", null, null, null),
              new CapabilityInput("helper", "http-sender", null, HttpMethod.GET,
                  example.getKey(), null, null, null)),
          List.of(), new DraftSettings(false, null));
      var pending = RequirementCaptureProjection.pending(input, List.of());
      RequirementDraft draft = RequirementCaptureProjection.toDraft(
          input, null, List.of(), "1", "hash");

      assertEquals(example.getValue(), draft.readyForPlan(), example.getKey());
      assertEquals(example.getValue(), pending.isEmpty(), example.getKey());
      assertTrue(draft.facts().stream().anyMatch(f -> f.sourceFactId().equals("helper-behavior")
          && f.text().equals(input.facts().getFirst().text())));
      if (!example.getValue()) {
        assertEquals("helper", pending.getFirst().interactionId());
        assertTrue(pending.getFirst().message().contains("full runtime HTTP URI"));
        assertTrue(pending.getFirst().message().contains("resolve the catalog operation"));
        assertTrue(pending.getFirst().message().contains("Do not invent a host"));
      }
    }
  }

  @Test
  void reviewDistinguishesRepeatedCallsWithoutExposingCaptureIds() {
    DraftInput draft = new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("entry-secret-id", Direction.INBOUND, "Client", "POST /tasks",
                null, null, null),
            new InteractionInput("first-secret-id", Direction.OUTBOUND, "Task API", "submit",
                null, null, new RetryInput(0, null)),
            new InteractionInput("second-secret-id", Direction.OUTBOUND, "Task API", "submit",
                null, null, null)),
            List.of(new TransitionInput("entry-secret-id", "first-secret-id"),
                new TransitionInput("first-secret-id", "second-secret-id"))),
        List.of(new FactInput("private-fact-id", List.of("first-secret-id", "second-secret-id"),
            FactKind.CONSTRAINT, Polarity.NEGATIVE, "Do not log the task body.")),
        List.of(new CapabilityInput("entry-secret-id", "http-trigger", HttpMode.CUSTOM,
            HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput("first-secret-id", "http-sender", null, HttpMethod.POST,
                "https://example.com/tasks", null, null, null),
            new CapabilityInput("second-secret-id", "http-sender", null, HttpMethod.POST,
                "https://example.com/tasks", null, null, null)),
        List.of(), new DraftSettings(null, null));

    String review = RequirementCaptureProjection.render(draft, List.of());

    assertTrue(review.contains("Call 1: Task API submit"));
    assertTrue(review.contains("Call 2: Task API submit"));
    assertTrue(review.contains("After Call 1, run Call 2."));
    assertTrue(review.contains("Do not log the task body. (for Call 1, Call 2)"));
    assertFalse(review.contains("secret-id"));
    assertFalse(review.contains("NEGATIVE CONSTRAINT"));
    assertEquals(RequirementFactPolarity.NEGATIVE,
        RequirementCaptureProjection.facts(draft).stream()
            .filter(fact -> "first-secret-id-retry".equals(fact.sourceFactId()))
            .findFirst().orElseThrow().polarity());
  }

  @Test
  void derivedCapabilitiesDoNotReplaceAuthoredFactsWithTheSameInteractionIds() {
    DraftInput input = kafkaDraft(List.of(
        new FactInput("http-entry", List.of("http-entry"), FactKind.CONSTRAINT,
            Polarity.POSITIVE, "Require RBAC on the entry."),
        new FactInput("publish-kafka", List.of("publish-kafka"), FactKind.BEHAVIOR,
            Polarity.POSITIVE, "Preserve the published body."),
        new FactInput("publish-kafka-2", List.of("publish-kafka"), FactKind.CONSTRAINT,
            Polarity.NEGATIVE, "Do not log the body.")));

    RequirementDraft draft = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
    RequirementBrief brief = RequirementBriefProjector.project(new RequirementBrief(
        "Publish", List.of(), List.of(), List.of(), List.of(), "Publish", null, "Publish",
        draft.facts(), List.of(), List.of(), List.of(), List.of(), draft.flow(), List.of()));

    assertEquals(draft.facts().size(), draft.facts().stream().map(RequirementFact::sourceFactId).distinct().count());
    assertEquals(3, draft.facts().stream().filter(fact -> input.facts().stream()
        .anyMatch(authored -> authored.sourceFactId().equals(fact.sourceFactId())
            && authored.text().equals(fact.text()))).count());
    assertTrue(RequirementFlowValidator.validateBindings(draft.flow(), draft.facts(), List.of()).isEmpty());
    assertEquals("http-entry", brief.entryPoints().getFirst().entryPointId());
    assertEquals("http-trigger", brief.entryPoints().getFirst().capabilityKey());
    assertEquals(capabilityId(draft.facts(), "http-trigger"), brief.entryPoints().getFirst().sourceFactId());
    assertTrue(brief.serviceCalls().isEmpty());
    assertEquals("publish-kafka-3", capabilityId(draft.facts(), "kafka-sender-2"));
  }

  @Test
  void derivedRetryFactKeepsRetryCountZeroWhenItsPreferredIdIsAlreadyAuthored() {
    DraftInput draft = kafkaDraft(List.of(
        new FactInput("publish-kafka-retry", List.of("publish-kafka"),
            FactKind.BEHAVIOR, Polarity.NEGATIVE, "Publish with retry count 0.")));

    List<RequirementFact> facts = RequirementCaptureProjection.facts(draft);
    RequirementFact authored = facts.stream()
        .filter(fact -> "publish-kafka-retry".equals(fact.sourceFactId()))
        .findFirst().orElseThrow();
    RequirementFact derived = facts.stream()
        .filter(fact -> fact.kind() == RequirementFactKind.BEHAVIOR)
        .filter(fact -> fact.text().equals("Do not retry this call."))
        .findFirst().orElseThrow();

    assertEquals(facts.size(), facts.stream().map(RequirementFact::sourceFactId).distinct().count());
    assertEquals("Publish with retry count 0.", authored.text());
    assertEquals(RequirementFactPolarity.NEGATIVE, derived.polarity());
    assertEquals(List.of("publish-kafka"), derived.interactionIds());
    assertEquals("publish-kafka", capabilityId(facts, "kafka-sender-2"));
    assertEquals("http-entry", capabilityId(facts, "http-trigger"));
  }

  @Test
  void derivedRetryIdAvoidsAnotherInteractionIdAndATakenSuffix() {
    DraftInput draft = new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("step", Direction.OUTBOUND, "Kafka", "publish",
                null, null, new RetryInput(0, null)),
            new InteractionInput("step-retry", Direction.OUTBOUND, "Kafka", "publish-other",
                null, null, new RetryInput(1, 1000))),
            List.of()),
        List.of(
            new FactInput("step-retry-2", List.of("step"), FactKind.CONSTRAINT, Polarity.NEGATIVE,
                "Do not change the topic.")),
        List.of(
            new CapabilityInput("step", "kafka-sender-2", null, null, null, "orders", null, null),
            new CapabilityInput("step-retry", "kafka-sender-2", null, null, null, "receipts", null, null)),
        List.of(), new DraftSettings(null, null));

    List<RequirementFact> facts = RequirementCaptureProjection.facts(draft);

    assertEquals(facts.size(), facts.stream().map(RequirementFact::sourceFactId).distinct().count());
    assertEquals("step", capabilityId(facts, "orders"));
    assertEquals("step-retry", capabilityId(facts, "receipts"));
    assertTrue(facts.stream().anyMatch(fact ->
        "step-retry-3".equals(fact.sourceFactId())
            && fact.polarity() == RequirementFactPolarity.NEGATIVE
            && fact.text().equals("Do not retry this call.")));
    assertTrue(facts.stream().anyMatch(fact ->
        "step-retry-retry".equals(fact.sourceFactId())
            && fact.polarity() == RequirementFactPolarity.POSITIVE
            && fact.text().contains("1 times")));
  }

  @Test
  void resolvedCatalogTargetsUseTheInteractionWhenTheCapabilityIdMoves() {
    for (String key : List.of("chain-call-2", "mcp-trigger")) {
      String owner = "catalog-owner";
      DraftInput input = new DraftInput(
          new FlowInput(List.of(new InteractionInput(owner,
              "mcp-trigger".equals(key) ? Direction.INBOUND : Direction.OUTBOUND,
              "Target service", "start", null, null, null)), List.of()),
          List.of(new FactInput(owner, List.of(owner), FactKind.BEHAVIOR,
              Polarity.POSITIVE, "Preserve context.")),
          List.of(new CapabilityInput(owner, key, null, null, null, null, null, null)),
          List.of(), new DraftSettings(null, null));
      Map<String, String> target = Map.of(owner, "catalog-target-id");

      RequirementDraft first = RequirementCaptureProjection.toDraft(
          input, null, List.of(), "1", "hash", target);
      RequirementDraft refreshed = RequirementCaptureProjection.toDraft(
          input, first, List.of(), "1", "hash");

      assertEquals(owner + "-2", capabilityId(first.facts(), key));
      assertEquals(target, RequirementCaptureProjection.resolvedTargets(first));
      assertEquals(target, RequirementCaptureProjection.resolvedTargets(refreshed));
      assertEquals(first.facts(), refreshed.facts());
    }
  }

  @Test
  void chainCallTargetLookupKeepsTheInteractionIdWhenRetryAllocationMoves() {
    DraftInput draft = new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("http-entry", Direction.INBOUND, "Client", "GET /call",
                null, null, null),
            new InteractionInput("call-chain", Direction.OUTBOUND, "Header chain", "start",
                null, null, new RetryInput(0, null))),
            List.of(new TransitionInput("http-entry", "call-chain"))),
        List.of(new FactInput("call-chain-retry", List.of("call-chain"),
            FactKind.BEHAVIOR, Polarity.NEGATIVE, "Call once.")),
        List.of(
            new CapabilityInput("http-entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
                "/call", null, null, null),
            new CapabilityInput("call-chain", "chain-call-2", null, null, null, null, null, null)),
        List.of(), new DraftSettings(null, null));
    Map<String, String> targets = Map.of("call-chain", "trig-header");

    List<RequirementFact> facts = RequirementCaptureProjection.facts(draft, targets);
    RequirementDraft projected = RequirementCaptureProjection.toDraft(
        draft, null, List.of(), "1", "hash", targets);
    RequirementDraft refreshed = RequirementCaptureProjection.toDraft(
        draft, projected, List.of(), "1", "hash", targets);

    assertEquals(facts.size(), facts.stream().map(RequirementFact::sourceFactId).distinct().count());
    assertEquals("call-chain", capabilityId(facts, "chain-call-2"));
    assertEquals("trig-header", facts.stream()
        .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
        .findFirst().orElseThrow().path());
    assertEquals(Map.of("call-chain", "trig-header"),
        RequirementCaptureProjection.resolvedTargets(projected));
    assertEquals(Map.of("call-chain", "trig-header"),
        RequirementCaptureProjection.resolvedTargets(refreshed));
    assertEquals(
        projected.facts().stream().map(RequirementFact::sourceFactId).collect(Collectors.toList()),
        refreshed.facts().stream().map(RequirementFact::sourceFactId).collect(Collectors.toList()));
  }

  @Test
  void collidingKafkaDraftSurvivesBriefCoverageAfterRefresh() {
    DraftInput input = kafkaDraft(List.of(
        new FactInput("publish-kafka-retry", List.of("publish-kafka"),
            FactKind.BEHAVIOR, Polarity.NEGATIVE, "Publish with retry count 0."),
        new FactInput("deliver-event", List.of(), FactKind.GOAL, Polarity.POSITIVE,
            "Deliver the event.")));
    RequirementDraft projected = RequirementCaptureProjection.toDraft(
        input, null, List.of(), "1", "hash");
    RequirementDraft refreshed = RequirementCaptureProjection.toDraft(
        input, projected, List.of(), "1", "hash");
    RequirementBrief brief = new RequirementBrief(
        "Deliver the event",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "summary",
        "ref",
        refreshed.planningText(),
        refreshed.facts(),
        List.of(new RequirementEntryPoint(
            "http-entry", "http-entry", "http-trigger", "", "POST", "/events", "POST /events")),
        List.of(),
        List.of(),
        List.of(),
        refreshed.flow(),
        List.of(),
        List.of());

    assertEquals(Optional.empty(), new RequirementBriefCoverageValidator().validate(refreshed, brief));
  }

  private static String capabilityId(List<RequirementFact> facts, String marker) {
    return facts.stream()
        .filter(fact -> fact.kind() == RequirementFactKind.CAPABILITY)
        .filter(fact -> marker.equals(fact.capabilityKey()) || marker.equals(fact.topic()))
        .map(RequirementFact::sourceFactId)
        .findFirst()
        .orElseThrow();
  }

  private static DraftInput kafkaDraft(List<FactInput> facts) {
    return new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("http-entry", Direction.INBOUND, "Client", "POST /events",
                null, null, null),
            new InteractionInput("publish-kafka", Direction.OUTBOUND, "Orders", "publish",
                null, null, new RetryInput(0, null))),
            List.of(new TransitionInput("http-entry", "publish-kafka"))),
        facts,
        List.of(
            new CapabilityInput("http-entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
                "/events", null, null, null),
            new CapabilityInput("publish-kafka", "kafka-sender-2", null, null, null,
                "orders", null, null)),
        List.of(), new DraftSettings(null, null));
  }
}
