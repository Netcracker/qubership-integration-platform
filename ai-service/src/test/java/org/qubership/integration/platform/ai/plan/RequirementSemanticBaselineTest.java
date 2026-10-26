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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementSemanticBaselineTest {

  private static final String CLAIM = "Submit one task";
  private static final String AUTHOR = "Submit one task before Friday.";

  @Test
  void quoteMustBeAnExactSubstringOfTheNamedUserTurn() {
    var packet = packet("turn-1", AUTHOR);
    var evidence = new ClaimEvidenceInput("turn-1", CLAIM);
    var fact = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);

    assertTrue(RequirementClaimEvidenceValidator.validate(null, List.of(fact), packet, List.of()).isEmpty());

    var forged = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null,
        new ClaimEvidenceInput("turn-1", "Submit two tasks"));
    var wrongTurn = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null,
        new ClaimEvidenceInput("turn-2", CLAIM));
    var missing = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, null);

    assertCode(RequirementClaimEvidenceValidator.validate(null, List.of(forged), packet, List.of()),
        "SOURCE_EVIDENCE_REQUIRED", "exact substring");
    assertCode(RequirementClaimEvidenceValidator.validate(null, List.of(wrongTurn), packet, List.of()),
        "SOURCE_EVIDENCE_REQUIRED", "exact substring");
    assertCode(RequirementClaimEvidenceValidator.validate(null, List.of(missing), packet, List.of()),
        "SOURCE_EVIDENCE_REQUIRED", "quote");
  }

  @Test
  void representationChangeKeepsTheClaim() {
    var packet = packet("turn-1", AUTHOR);
    var evidence = new ClaimEvidenceInput("turn-1", CLAIM);
    var original = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);
    var baseline = RequirementClaimEvidenceValidator.reconcile(null, List.of(original), packet, List.of());
    var repaired = fact("goal", List.of("entry", "submit"), FactKind.BEHAVIOR, Polarity.POSITIVE, CLAIM,
        new FieldMappingInput("entry", "$.Subject", "submit", "$.Task", null),
        new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.BEFORE_HOOK,
            "entry", null, null, null, null, List.of(), List.of("task")),
        null);

    assertTrue(RequirementClaimEvidenceValidator.validate(baseline, List.of(repaired), packet, List.of()).isEmpty(),
        "Placement, literal binding, kind, interactions, and mapping are not the claim");
    var next = RequirementClaimEvidenceValidator.reconcile(baseline, List.of(repaired), packet, List.of());

    assertEquals(baseline.claims(), next.claims());
    assertEquals(List.of(new RequirementSemanticClaim("goal", CLAIM, Polarity.POSITIVE, evidence)), next.claims());
    assertEquals(FactKind.BEHAVIOR, repaired.kind());
    assertEquals(List.of("task"), repaired.elementIntent().exactValues());
    assertEquals(RequirementElementIntent.Placement.BEFORE_HOOK, repaired.elementIntent().placement());
  }

  @Test
  void textPolarityAndDeletionRequireASourceLinkedAmendment() {
    var packet = packet("turn-1", AUTHOR);
    var evidence = new ClaimEvidenceInput("turn-1", CLAIM);
    var original = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);
    var baseline = RequirementClaimEvidenceValidator.reconcile(null, List.of(original), packet, List.of());
    var changedText = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit two tasks",
        null, null, null);
    var changedPolarity = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.NEGATIVE, CLAIM, null, null, null);

    assertCode(RequirementClaimEvidenceValidator.validate(baseline, List.of(changedText), packet, List.of()),
        "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");
    assertCode(RequirementClaimEvidenceValidator.validate(baseline, List.of(changedPolarity), packet, List.of()),
        "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");
    assertCode(RequirementClaimEvidenceValidator.validate(baseline, List.of(), packet, List.of()),
        "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");

    var revised = packet("turn-1", AUTHOR, "turn-2", "Submit two tasks.");
    var textAmendment = List.of(new AmendmentInput("/facts/goal/text", "turn-2", "Submit two tasks"));
    assertTrue(RequirementClaimEvidenceValidator.validate(baseline, List.of(changedText), revised, textAmendment).isEmpty());
    var updated = RequirementClaimEvidenceValidator.reconcile(baseline, List.of(changedText), revised, textAmendment);
    assertEquals(List.of(new RequirementSemanticClaim("goal", "Submit two tasks", Polarity.POSITIVE,
        new ClaimEvidenceInput("turn-2", "Submit two tasks"))), updated.claims());

    var removal = List.of(new AmendmentInput("/facts/goal", "turn-2", "Submit two tasks"));
    assertTrue(RequirementClaimEvidenceValidator.validate(baseline, List.of(), revised, removal).isEmpty());
    assertTrue(RequirementClaimEvidenceValidator.reconcile(baseline, List.of(), revised, removal).claims().isEmpty());
  }

  @Test
  void newFactMissingFromTheBaselineRequiresEvidence() {
    var packet = packet("turn-1", AUTHOR);
    var evidence = new ClaimEvidenceInput("turn-1", CLAIM);
    var saved = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);
    var baseline = RequirementClaimEvidenceValidator.reconcile(null, List.of(saved), packet, List.of());
    var added = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, null);
    var forged = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, new ClaimEvidenceInput("turn-1", "next Monday"));
    var proven = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, new ClaimEvidenceInput("turn-1", "before Friday"));

    assertCode(RequirementClaimEvidenceValidator.validate(baseline, List.of(saved, added), packet, List.of()),
        "SOURCE_EVIDENCE_REQUIRED", "deadline");
    assertCode(RequirementClaimEvidenceValidator.validate(baseline, List.of(saved, forged), packet, List.of()),
        "SOURCE_EVIDENCE_REQUIRED", "exact substring");
    assertTrue(RequirementClaimEvidenceValidator.validate(baseline, List.of(saved, proven), packet, List.of()).isEmpty());
    assertTrue(RequirementClaimEvidenceValidator.validate(baseline, List.of(saved), packet, List.of()).isEmpty(),
        "A saved claim can be replayed without repeating its quote");
  }

  @Test
  void claimSurvivesRepresentationRepairAndRestart() throws Exception {
    var world = world();
    var source = ready(world, AUTHOR);
    var evidence = new ClaimEvidenceInput(source.turnId(), CLAIM);
    var original = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);
    var captured = capture(world, draft(List.of(original)));
    assertTrue(captured.accepted(), captured.toString());

    var repairedFact = fact("goal", List.of("entry", "submit"), FactKind.FIELD_MAPPING, Polarity.POSITIVE, CLAIM,
        new FieldMappingInput("entry", "$.Subject", "submit", "$.Task", null),
        new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.BEFORE_HOOK,
            "entry", null, null, null, null, List.of(), List.of("task")),
        null);
    var repaired = update(world, updateFacts(repairedFact));
    assertTrue(repaired.accepted(), repaired.toString());

    var stored = world.store().get(world.chat()).orElseThrow().authoredDraft().facts().getFirst();
    assertEquals(FactKind.FIELD_MAPPING, stored.kind());
    assertEquals(List.of("entry", "submit"), stored.interactionIds());
    assertEquals("$.Task", stored.fieldMapping().targetPath());
    assertEquals(RequirementElementIntent.Placement.BEFORE_HOOK, stored.elementIntent().placement());
    assertEquals(List.of("task"), stored.elementIntent().exactValues());

    var restarted = restart(world);
    var baseline = restarted.store().semanticBaseline(restarted.chat()).orElseThrow();
    var packet = AuthorSourceEvidence.packet(restarted.archive().read(restarted.chat()));
    assertEquals(packet.generation(), baseline.sourceGeneration());
    assertEquals(CaptureProposal.digest(packet), baseline.authorEvidenceHash());
    assertEquals(List.of(new RequirementSemanticClaim("goal", CLAIM, Polarity.POSITIVE, evidence)), baseline.claims());
    var revision = restarted.artifacts().latest(
        restarted.sessions().current(restarted.chat()).orElseThrow().compilationId(),
        Kind.REQUIREMENT_SEMANTIC_BASELINE).orElseThrow();
    assertEquals(Kind.REQUIREMENT_SEMANTIC_BASELINE, revision.kind());
    var claim = revision.payload().path("claims").get(0);
    assertFalse(claim.has("kind"));
    assertFalse(claim.has("interactionIds"));
    assertFalse(claim.has("fieldMapping"));
    assertFalse(claim.has("elementIntent"));
    assertFalse(claim.has("exactValues"));
    assertEquals(FactKind.FIELD_MAPPING, restarted.store().get(restarted.chat()).orElseThrow()
        .authoredDraft().facts().getFirst().kind());
  }

  @Test
  void acceptedDraftRefusesClaimChangesWithoutANewSourceLinkedAmendment() throws Exception {
    var world = world();
    var source = ready(world, AUTHOR);
    var evidence = new ClaimEvidenceInput(source.turnId(), CLAIM);
    var original = fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM, null, null, evidence);
    assertTrue(capture(world, draft(List.of(original))).accepted());
    var saved = world.store().semanticBaseline(world.chat()).orElseThrow();

    var changedText = update(world, updateFacts(
        fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit two tasks", null, null, null)));
    assertFalse(changedText.accepted(), changedText.toString());
    assertIssue(changedText, "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");

    var changedPolarity = update(world, updateFacts(
        fact("goal", List.of("submit"), FactKind.GOAL, Polarity.NEGATIVE, CLAIM, null, null, null)));
    assertFalse(changedPolarity.accepted(), changedPolarity.toString());
    assertIssue(changedPolarity, "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");

    var deleted = update(world, removeFact("goal"));
    assertFalse(deleted.accepted(), deleted.toString());
    assertIssue(deleted, "AUTHOR_AMENDMENT_REQUIRED", "source-linked amendment");
    assertEquals(saved, world.store().semanticBaseline(world.chat()).orElseThrow());

    var revision = ready(world, "Submit two tasks.");
    var amended = update(world, new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(fact("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit two tasks", null, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
        List.of(new AmendmentInput("/facts/goal/text", revision.turnId(), "Submit two tasks"))));
    assertTrue(amended.accepted(), amended.toString());
    assertEquals("Submit two tasks", world.store().semanticBaseline(world.chat()).orElseThrow().claims().getFirst().text());
    assertEquals(new ClaimEvidenceInput(revision.turnId(), "Submit two tasks"),
        world.store().semanticBaseline(world.chat()).orElseThrow().claims().getFirst().evidence());
  }

  @Test
  void legacyAcceptedDraftStaysReadableWithoutAQuote() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var legacyFact = mapper.readValue("""
        {"sourceFactId":"goal","interactionIds":["submit"],"kind":"GOAL","polarity":"POSITIVE","text":"Submit one task."}
        """, FactInput.class);
    assertNull(legacyFact.evidence());
    assertEquals(new FactInput("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit one task."),
        legacyFact);
    var legacyDraft = mapper.readValue("""
        {"flow":{"interactions":[{"interactionId":"entry","direction":"INBOUND","participant":"Client",
        "operation":"POST /tasks"}],"transitions":[]},"facts":[{"sourceFactId":"goal","interactionIds":["submit"],
        "kind":"GOAL","polarity":"POSITIVE","text":"Submit one task."}],"capabilities":[],"openQuestions":[],
        "settings":{"idsRequested":null,"preferredSystemType":null}}
        """, DraftInput.class);
    assertNull(legacyDraft.facts().getFirst().evidence());

    var world = world();
    ready(world, "Submit one task.");
    var input = draft(List.of(new FactInput("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE,
        "Submit one task.")));
    var accepted = capture(world, input);
    assertTrue(accepted.accepted(), accepted.toString());
    assertNull(world.store().get(world.chat()).orElseThrow().authoredDraft().facts().getFirst().evidence());
    assertEquals(List.of(new RequirementSemanticClaim("goal", "Submit one task.", Polarity.POSITIVE, null)),
        world.store().semanticBaseline(world.chat()).orElseThrow().claims());
    var attempt = world.artifacts().latest(
        world.sessions().current(world.chat()).orElseThrow().compilationId(),
        Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    assertFalse(attempt.payload().path("arguments").toString().contains("evidence"));
    assertEquals(CaptureProposal.digest(input), CaptureProposal.digest(attempt.payload().path("arguments")));
    assertFalse(mapper.valueToTree(legacyFact).has("evidence"));
  }

  @Test
  void laterFactOnAGrandfatheredDraftRequiresEvidence() throws Exception {
    var world = world();
    var source = ready(world, AUTHOR);
    var acceptedFact = new FactInput("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, CLAIM);
    assertTrue(capture(world, draft(List.of(acceptedFact))).accepted());
    var saved = world.store().semanticBaseline(world.chat()).orElseThrow();
    assertEquals(List.of(new RequirementSemanticClaim("goal", CLAIM, Polarity.POSITIVE, null)), saved.claims());

    var replay = update(world, updateFacts(acceptedFact));
    assertTrue(replay.accepted(), replay.toString());
    assertEquals(saved.claims(), world.store().semanticBaseline(world.chat()).orElseThrow().claims());

    var missing = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, null);
    var rejected = update(world, addFact(missing));
    assertFalse(rejected.accepted(), rejected.toString());
    assertIssue(rejected, "SOURCE_EVIDENCE_REQUIRED", "deadline");
    assertEquals(saved.claims(), world.store().semanticBaseline(world.chat()).orElseThrow().claims());

    var forged = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, new ClaimEvidenceInput(source.turnId(), "next Monday"));
    assertIssue(update(world, addFact(forged)), "SOURCE_EVIDENCE_REQUIRED", "exact substring");

    var proven = fact("deadline", List.of("submit"), FactKind.CONSTRAINT, Polarity.POSITIVE, "before Friday",
        null, null, new ClaimEvidenceInput(source.turnId(), "before Friday"));
    var added = update(world, addFact(proven));
    assertTrue(added.accepted(), added.toString());
    assertEquals(List.of(
        new RequirementSemanticClaim("goal", CLAIM, Polarity.POSITIVE, null),
        new RequirementSemanticClaim("deadline", "before Friday", Polarity.POSITIVE,
            new ClaimEvidenceInput(source.turnId(), "before Friday"))),
        world.store().semanticBaseline(world.chat()).orElseThrow().claims());
  }

  @Test
  void captureSchemaDoesNotRequireEvidence() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var provider = new RequirementCaptureToolProvider(new RequirementCaptureTools(new RequirementDraftStore(),
        mock(RequirementDraftTool.class), mapper), mapper).provideTools(null);
    for (String tool : List.of("captureRequirementDraft", "updateRequirementDraft")) {
      String description = provider.toolSpecificationByName(tool).description();
      assertTrue(description.contains("exact quote"), description);
      assertTrue(description.contains("semantic baseline"), description);
    }
    var draft = (JsonObjectSchema) provider.toolSpecificationByName("captureRequirementDraft")
        .parameters().properties().get("draft");
    var facts = (JsonArraySchema) draft.properties().get("facts");
    var fact = (JsonObjectSchema) facts.items();
    assertFalse(fact.required().contains("evidence"));
    var evidence = (JsonAnyOfSchema) fact.properties().get("evidence");
    assertTrue(evidence.description().contains("exact quote"), evidence.description());
  }

  private static void assertCode(List<Issue> issues, String code, String messagePart) {
    assertTrue(issues.stream().anyMatch(issue -> code.equals(issue.code())
        && issue.message() != null && issue.message().contains(messagePart)), issues.toString());
  }

  private static void assertIssue(RequirementCaptureResult result, String code, String messagePart) {
    assertTrue(result.issues().stream().anyMatch(issue -> code.equals(issue.code())
        && issue.message() != null && issue.message().contains(messagePart)), result.toString());
  }

  private static AuthorSourceEvidence.Packet packet(String turnId, String text) {
    return new AuthorSourceEvidence.Packet("generation", List.of(new AuthorSourceEvidence.Input(turnId, text)));
  }

  private static AuthorSourceEvidence.Packet packet(String firstId, String first, String secondId, String second) {
    return new AuthorSourceEvidence.Packet("generation", List.of(
        new AuthorSourceEvidence.Input(firstId, first), new AuthorSourceEvidence.Input(secondId, second)));
  }

  private static FactInput fact(String id, List<String> interactions, FactKind kind, Polarity polarity, String text,
      FieldMappingInput mapping, RequirementElementIntent intent, ClaimEvidenceInput evidence) {
    return new FactInput(id, interactions, kind, polarity, text, mapping, null, intent, evidence);
  }

  private static DraftInput draft(List<FactInput> facts) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Client", "POST /tasks", null, null, null),
        new InteractionInput("submit", Direction.OUTBOUND, "Task API", "submit", null, null, null)),
        List.of(new TransitionInput("entry", "submit"))), facts, List.of(
            new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput("submit", "http-sender", null, HttpMethod.POST, "https://example.com/tasks",
                null, null, null)),
        List.of(), new DraftSettings(null, null));
  }

  private static DraftUpdate addFact(FactInput fact) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(fact), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
  }

  private static DraftUpdate updateFacts(FactInput fact) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(fact),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
  }

  private static DraftUpdate removeFact(String id) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(id), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
  }

  private static RequirementCaptureResult capture(World world, DraftInput draft) throws Exception {
    try (var ignored = ToolSession.open(world.chat())) {
      return world.mapper().readValue(world.tools().captureRequirementDraft(draft), RequirementCaptureResult.class);
    }
  }

  private static RequirementCaptureResult update(World world, DraftUpdate changes) throws Exception {
    try (var ignored = ToolSession.open(world.chat())) {
      return world.mapper().readValue(world.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
    }
  }

  private static ConversationArchive.Input ready(World world, String text) {
    var source = world.archive().beginInput(world.chat(), text, null);
    world.archive().resolveInput(world.chat(), source, text);
    world.store().beginTurn(world.chat(), text);
    return source;
  }

  private static World world() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    return new World(blobs, mapper, artifacts, sessions, archive, store,
        new RequirementCaptureTools(store, adapter, mapper), "semantic-" + UUID.randomUUID());
  }

  private static World restart(World world) {
    var artifacts = new CompilationArtifacts(world.blobs(), world.mapper(), Clock.systemUTC());
    var sessions = new CompilationSessions(world.blobs(), world.mapper(), Clock.systemUTC());
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(world.archive());
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    return new World(world.blobs(), world.mapper(), artifacts, sessions, world.archive(), store,
        new RequirementCaptureTools(store, adapter, world.mapper()), world.chat());
  }

  private record World(
      InMemoryArtifactBlobStore blobs,
      ObjectMapper mapper,
      CompilationArtifacts artifacts,
      CompilationSessions sessions,
      ConversationArchive archive,
      RequirementDraftStore store,
      RequirementCaptureTools tools,
      String chat) {}
}
