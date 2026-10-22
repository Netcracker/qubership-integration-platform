package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementBodyCompletionAdmissionTest {
  private static final String CODE = "exchange.message.body = 'preserved'\n";
  private static final String SOURCE = "Receive POST /legacy. prepare.script:\n```groovy\n" + CODE
      + "```\nProceed without IDS.";

  @Test
  void consumedOriginalSourceCanCompleteOnlyLegacyMetadataAfterReload() {
    var h = new Harness();
    var base = h.store.get(h.chat).orElseThrow();
    h.reload();
    var candidate = h.completed();
    ProductCapabilityCaptureContext.bindDiscovery("run", h.chat, base, Map.of(), Set.of(), value -> {});
    try {
      var issues = h.store.admitCapture(h.chat, h.reference, candidate, null, Map.of());
      assertTrue(issues.isEmpty(), issues.toString());
    } finally { ProductCapabilityCaptureContext.unbind(h.chat); }
    h.reload();
    assertEquals(candidate, h.store.get(h.chat).orElseThrow());
    assertNotEquals(h.reference, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(h.input, h.artifacts.payload(h.artifacts.get(h.store.activeCompilationId(h.chat),
        h.reference).orElseThrow(), RequirementDraft.class).authoredDraft());
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "owner", "type", "placement", "predecessor", "successor", "parent", "body",
      "config", "name", "text", "exact-values", "polarity", "fact-refs", "binding-owner", "binding-property",
      "binding-kind", "generation", "turn", "offset", "encoding", "authority", "source-turn", "source-generation",
      "source-text", "stale-base", "binding-catalog", "no-context", "null-binding"})
  void inheritedFindingPointersCannotAuthorizeCollateralOrUnprovenMetadata(String change) throws Exception {
    var h = new Harness();
    var accepted = h.store.get(h.chat).orElseThrow();
    var tree = h.json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(h.completed().authoredDraft());
    var intent = (com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0/elementIntent");
    var fact = (com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0");
    var binding = (com.fasterxml.jackson.databind.node.ObjectNode) intent.path("bodyBinding");
    switch (change) {
      case "id" -> intent.put("elementRef", "other");
      case "owner" -> intent.put("ownerInteractionId", "foreign");
      case "type" -> intent.put("elementType", "http-sender");
      case "placement" -> intent.put("placement", "BEFORE_HOOK");
      case "predecessor" -> intent.putNull("predecessorElementRef");
      case "successor" -> intent.put("successorElementRef", "entry");
      case "parent" -> intent.put("parentElementRef", "entry");
      case "body" -> ((com.fasterxml.jackson.databind.node.ObjectNode) intent.at("/requestedProperties/0"))
          .put("value", "return 'changed'");
      case "config" -> intent.withArray("requestedProperties").addObject().put("key", "other").put("value", "changed");
      case "name" -> intent.put("requestedName", "Changed name");
      case "text" -> fact.put("text", "Changed author requirement.");
      case "exact-values" -> intent.withArray("exactValues").removeAll();
      case "polarity" -> fact.put("polarity", "NEGATIVE");
      case "fact-refs" -> fact.withArray("interactionIds").removeAll();
      case "binding-owner" -> binding.put("elementRef", "foreign");
      case "binding-property" -> binding.put("propertyKey", "other");
      case "binding-kind" -> binding.putNull("bodyKind");
      case "generation" -> binding.put("sourceGeneration", "obsolete");
      case "turn" -> binding.put("sourceTurnId", "foreign");
      case "offset" -> binding.put("startOffset", 0);
      case "encoding" -> binding.put("encoding", "JSON_STRING");
      case "authority" -> binding.put("authority", "GENERATED_CANDIDATE");
      case "source-turn" -> h.addSource("Preserve every field.");
      case "source-generation" -> h.conversations.clearMessages(h.chat);
      case "source-text" -> h.replaceArchivedText();
      case "stale-base", "binding-catalog", "no-context" -> { }
      case "null-binding" -> intent.putNull("bodyBinding");
      default -> throw new AssertionError(change);
    }
    if (!change.equals("no-context")) ProductCapabilityCaptureContext.bindDiscovery("run", h.chat,
        change.equals("stale-base") ? h.completed() : accepted, Map.of(),
        Set.of("/facts/body/elementIntent/bodyBinding"), value -> {});
    try {
      var candidate = h.project(h.json.convertValue(tree, DraftInput.class));
      if (change.equals("binding-catalog")) candidate = RequirementCaptureProjection.toDraft(candidate.authoredDraft(), null,
          List.of(RequirementFailureConsistencyTest.binding("entry")), "legacy", "legacy");
      var issues = h.store.admitCapture(h.chat, h.reference, candidate, null, Map.of());
      if (change.equals("null-binding")) assertTrue(issues.isEmpty(), issues.toString());
      else assertFalse(issues.isEmpty(), change + ": " + issues);
      assertEquals(h.input, h.store.get(h.chat).orElseThrow().authoredDraft());
    } finally { ProductCapabilityCaptureContext.unbind(h.chat); }
  }

  @Test
  void twoIndependentCompletionsAndAnHttpModeCorrectionCompose() {
    var h = new Harness();
    var base = RequirementFailureConsistencyAdmissionTest.input();
    var facts = new java.util.ArrayList<>(base.facts());
    facts.set(2, new FactInput("render-result", List.of("remote"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Render each response and deliver its success or failure fields.", null, null,
        new RequirementElementIntent("response", "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "remote", "delivery", "remote", "Render result", List.of(new PlanProperty("script", "generated hint")), List.of())));
    facts.add(h.input.facts().getFirst());
    var input = new DraftInput(base.flow(), facts, base.capabilities(), base.openQuestions(), base.settings());
    var source = new AuthorSourceEvidence.Packet("generation", List.of(new AuthorSourceEvidence.Input("turn",
        "Create two Groovy scripts implementing the requested behavior.")));
    var owners = facts.stream().map(FactInput::elementIntent).filter(java.util.Objects::nonNull).toList();
    var tree = h.json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(input);
    for (int index : List.of(2, facts.size() - 1)) {
      var intent = facts.get(index).elementIntent();
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/" + index + "/elementIntent"))
          .set("bodyBinding", h.json.valueToTree(RequirementBodySources.resolve(intent, owners, source).binding()));
    }
    var completed = h.json.convertValue(tree, DraftInput.class);
    var paths = RequirementBodySources.completionPaths(input, completed, source);
    assertEquals(2, paths.size());
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/flow/interactions/1"))
        .put("failureMode", ServiceCallFailureMode.INLINE_RESPONSE.name());
    var candidate = h.json.convertValue(tree, DraftInput.class);
    assertTrue(RequirementFailureConsistency.correctsOnlyModes(input,
        RequirementBodySources.normalizeCompletedBindings(candidate, paths),
        RequirementFailureConsistency.issues(input, RequirementFailureConsistencyAdmissionTest.bindings())));
    var repairs = new java.util.HashSet<>(paths);
    repairs.add("/interactions/remote/failureMode");
    assertTrue(RequirementRevisionAdmission.validate(input, candidate, List.of(), null, Set.of(), repairs, Map.of(), fact -> false).isEmpty());
    var restored = new Harness(input, source.inputs().getFirst().text(), RequirementFailureConsistencyAdmissionTest.bindings());
    var archived = AuthorSourceEvidence.packet(restored.conversations.archive().read(restored.chat));
    for (int index : List.of(2, facts.size() - 1)) {
      var intent = facts.get(index).elementIntent();
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/" + index + "/elementIntent"))
          .set("bodyBinding", restored.json.valueToTree(RequirementBodySources.resolve(intent, owners, archived).binding()));
    }
    ProductCapabilityCaptureContext.bindDiscovery("run", restored.chat, restored.store.get(restored.chat).orElseThrow(),
        Map.of(), Set.of(), value -> {});
    try {
      var issues = restored.store.admitCapture(restored.chat, restored.reference,
          restored.project(restored.json.convertValue(tree, DraftInput.class)), null, Map.of());
      assertTrue(issues.isEmpty(), issues.toString());
    } finally { ProductCapabilityCaptureContext.unbind(restored.chat); }
  }

  @Test
  void anExistingBindingCannotBeReplacedOrDowngradedByCompletionAuthority() {
    var h = new Harness();
    var complete = h.completed().authoredDraft();
    var packet = AuthorSourceEvidence.packet(h.conversations.archive().read(h.chat));
    assertTrue(RequirementBodySources.completionPaths(complete, h.input, packet).isEmpty());
    var tree = h.json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(complete);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0/elementIntent/bodyBinding"))
        .put("authority", "GENERATED_CANDIDATE");
    assertTrue(RequirementBodySources.completionPaths(complete, h.json.convertValue(tree, DraftInput.class), packet).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-source", "missing-generation", "missing-turn", "duplicate-turn", "ambiguous-body",
      "unbounded-body", "foreign-source-owner", "wrong-complete-body", "duplicate-owner", "negative-owner"})
  void onlyCompleteUnambiguousSourceAndUniqueNativeOwnershipCanCompleteMetadata(String change) {
    var h = new Harness();
    var base = h.input;
    var candidate = h.completed().authoredDraft();
    var source = AuthorSourceEvidence.packet(h.conversations.archive().read(h.chat));
    switch (change) {
      case "missing-source" -> source = new AuthorSourceEvidence.Packet(source.generation(), List.of());
      case "missing-generation" -> source = new AuthorSourceEvidence.Packet(null, source.inputs());
      case "missing-turn" -> source = new AuthorSourceEvidence.Packet(source.generation(),
          List.of(new AuthorSourceEvidence.Input(null, SOURCE)));
      case "duplicate-turn" -> source = new AuthorSourceEvidence.Packet(source.generation(),
          List.of(source.inputs().getFirst(), source.inputs().getFirst()));
      case "ambiguous-body" -> source = new AuthorSourceEvidence.Packet(source.generation(),
          List.of(new AuthorSourceEvidence.Input(source.inputs().getFirst().sourceTurnId(), SOURCE + "\n" + SOURCE)));
      case "unbounded-body" -> source = new AuthorSourceEvidence.Packet(source.generation(),
          List.of(new AuthorSourceEvidence.Input(source.inputs().getFirst().sourceTurnId(), "Use this exact script: " + CODE)));
      case "foreign-source-owner" -> source = new AuthorSourceEvidence.Packet(source.generation(),
          List.of(new AuthorSourceEvidence.Input(source.inputs().getFirst().sourceTurnId(), SOURCE.replace("prepare.script", "foreign.script"))));
      case "wrong-complete-body", "negative-owner", "duplicate-owner" -> {
        var before = h.json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(base);
        var after = h.json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(candidate);
        for (var tree : List.of(before, after)) {
          if (change.equals("wrong-complete-body")) ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0/elementIntent/requestedProperties/0"))
              .put("value", "return 'not the complete source body'");
          else if (change.equals("negative-owner")) ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0")).put("polarity", "NEGATIVE");
          else {
            var duplicate = (com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0").deepCopy();
            duplicate.put("sourceFactId", "duplicate");
            tree.withArray("facts").add(duplicate);
          }
        }
        base = h.json.convertValue(before, DraftInput.class);
        candidate = h.json.convertValue(after, DraftInput.class);
      }
      default -> throw new AssertionError(change);
    }
    assertTrue(RequirementBodySources.completionPaths(base, candidate, source).isEmpty(), change);
  }

  @Test
  void nativePreservationChecksVerifyOnlySourceDerivedMetadataAndRemainReadOnlyAfterReload() throws Exception {
    var h = new Harness();
    var candidate = h.completed().authoredDraft();
    var tools = new RequirementCaptureTools(h.store, new RequirementDraftTool(h.store), h.json);
    tools.conversations = h.conversations;
    var schemas = DeterministicElementSchemaService.createForUnitTests(h.json);
    tools.elementSchemas = schemas;
    try (var ignored = ToolSession.open(h.chat)) {
      var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(candidate.facts().getFirst()), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var refusal = h.json.readTree(tools.updateRequirementDraft(update));
      assertFalse(refusal.path("accepted").asBoolean());
      assertTrue(refusal.path("issues").toString().contains("REPAIR_FACT_LOSS"), refusal.toString());
    }
    var attempt = h.artifacts.latest(h.store.activeCompilationId(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT)
        .orElseThrow().reference();
    var brief = RequirementBriefProjector.project(new RequirementBrief("Preserve the requested result", List.of(), List.of(),
        List.of(), List.of(), "", null, "", RequirementCaptureProjection.facts(candidate))
        .withFlow(RequirementCaptureProjection.flow(candidate)));
    var graph = new ChainPlanGraph("1.0", new ChainSection("Legacy", ""), List.of(
        new ChainPlanNode("entry", "http-trigger", "Entry", null, null, List.of()),
        new ChainPlanNode("prepare", "script", "Prepare result", null, null, List.of(new PlanProperty("script", CODE)))),
        List.of(new ChainPlanEdge("run", "entry", "prepare", null)));
    var inputs = new RequirementRevisionAdmission.NativeInputs(brief, graph);
    var source = h.conversations.archive().read(h.chat);
    var report = RequirementRevisionAdmission.evaluate(h.chat, attempt, h.store, h.artifacts, h.conversations, schemas, inputs);
    assertEquals(RequirementRevisionAdmission.PreservationOutcome.VERIFIED, report.outcome(), report.findings().toString());
    assertEquals(h.reference, h.store.latestRevision(h.chat).orElseThrow().reference());
    h.reload();
    assertEquals(report, RequirementRevisionAdmission.evaluate(h.chat, attempt, h.store, h.artifacts, h.conversations, schemas, inputs));
    assertEquals(source, h.conversations.archive().read(h.chat));
    assertEquals(h.input, h.store.get(h.chat).orElseThrow().authoredDraft());
  }

  @ParameterizedTest
  @ValueSource(strings = {"hashless", "source-text", "source-turn", "source-generation", "base", "foreign", "missing-consumers"})
  void nativeDiagnosticProofCannotUseStaleOrUnboundEvidence(String change) throws Exception {
    var h = new Harness();
    var candidate = h.completed().authoredDraft();
    var issues = List.of(new RequirementCaptureResult.CaptureIssue("REPAIR_FACT_LOSS", "/changes/facts/body", "body", null,
        "Metadata completion was rejected under the former preservation contract."));
    var document = h.conversations.archive().read(h.chat);
    String sourceHash = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(AuthorSourceEvidence.packet(document));
    var attempt = h.artifacts.append(new CompilationArtifacts.AppendCommand(h.store.activeCompilationId(h.chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", "requirement-capture-tools", "1",
        new RequirementDraftStore.RequirementCaptureAttempt(document.generation(),
            org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(document),
            SOURCE, h.store.captureMutationScope(h.chat), "captureRequirementDraft", candidate, false, issues, h.reference,
            "unchanged-native-context", null, change.equals("hashless") ? null : sourceHash), List.of(), null));
    switch (change) {
      case "source-text" -> h.replaceArchivedText();
      case "source-turn" -> h.addSource("Keep the existing behavior.");
      case "source-generation" -> h.conversations.clearMessages(h.chat);
      case "base" -> h.store.put(h.chat, RequirementCaptureProjection.toDraft(h.input, h.store.get(h.chat).orElseThrow(),
          List.of(), "different-derived-base", "different-derived-base"));
      case "hashless", "foreign", "missing-consumers" -> { }
      default -> throw new AssertionError(change);
    }
    var owner = change.equals("foreign") ? new Harness() : h;
    var report = RequirementRevisionAdmission.evaluate(owner.chat, attempt.reference(), owner.store, owner.artifacts,
        owner.conversations, DeterministicElementSchemaService.createForUnitTests(owner.json), null);
    assertNotEquals(RequirementRevisionAdmission.PreservationOutcome.VERIFIED, report.outcome(), report.findings().toString());
    if (!change.equals("missing-consumers")) assertTrue(report.findings().stream().anyMatch(finding ->
        finding.checker().equals("author-origin") && finding.outcome() == RequirementRevisionAdmission.PreservationOutcome.UNPROVEN)
        || report.findings().stream().anyMatch(finding -> finding.path().equals("/attempt")), report.toString());
  }

  static final class Harness {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final ConversationService conversations = conversations();
    final String chat = UUID.randomUUID().toString();
    RequirementDraftStore store = new RequirementDraftStore(artifacts, sessions, conversations);
    final DraftInput input;
    final CompilationArtifacts.Reference reference;
    final List<org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint> bindings;

    Harness() {
      this(null, SOURCE, List.of());
    }

    Harness(DraftInput restored, String source,
        List<org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint> bindings) {
      this.bindings = bindings;
      var turn = conversations.archive().beginInput(chat, source, null);
      conversations.archive().resolveInput(chat, turn, source);
      store.beginTurn(chat);
      var intent = new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.LOCAL_NODE,
          "entry", null, "entry", null, List.of(new PlanProperty("script", CODE)), List.of("preserved"));
      input = restored != null ? restored : withNativeAnchors(new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "POST /legacy", null, null, null)), List.of()), List.of(new FactInput("body", List.of("entry"),
              FactKind.BEHAVIOR, Polarity.POSITIVE, "Run the exact script producing preserved.", null, null, intent)),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/legacy",
              null, null, null)), List.of(), new DraftSettings(false, null)));
      store.put(chat, project(input));
      var accepted = store.latestRevision(chat).orElseThrow();
      reference = accepted.reference();
      conversations.archive().consume(chat, turn.generation(), List.of(turn.turnId()), accepted.artifactId());
      store.beginTurn(chat);
    }

    ConversationService conversations() {
      var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
          org.mockito.Mockito.RETURNS_DEEP_STUBS);
      org.mockito.Mockito.when(config.conversation().idleTimeout()).thenReturn(java.time.Duration.ofHours(1));
      return new ConversationService(config, new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive(blobs, json));
    }

    void reload() {
      store = new RequirementDraftStore(artifacts, new CompilationSessions(blobs, json, Clock.systemUTC()), conversations);
      store.beginTurn(chat);
    }

    void addSource(String text) {
      var turn = conversations.archive().beginInput(chat, text, null);
      conversations.archive().resolveInput(chat, turn, text);
    }

    void replaceArchivedText() throws Exception {
      String key = blobs.list("").stream().filter(value -> value.endsWith("/source.json")).findFirst().orElseThrow();
      var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(blobs.get(key).orElseThrow());
      ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/turns/0/message")).put("content", "Changed source.");
      blobs.put(key, json.writeValueAsBytes(source));
    }

    RequirementDraft completed() {
      var tree = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(input);
      var intent = input.facts().getFirst().elementIntent();
      var binding = RequirementBodySources.resolve(intent, input.facts().stream().map(FactInput::elementIntent)
          .filter(java.util.Objects::nonNull).toList(), AuthorSourceEvidence.packet(conversations.archive().read(chat)));
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/0/elementIntent"))
          .set("bodyBinding", json.valueToTree(binding.binding()));
      return project(json.convertValue(tree, DraftInput.class));
    }

    RequirementDraft project(DraftInput value) {
      return RequirementCaptureProjection.toDraft(value, null, bindings, "legacy", "legacy");
    }
  }
}
