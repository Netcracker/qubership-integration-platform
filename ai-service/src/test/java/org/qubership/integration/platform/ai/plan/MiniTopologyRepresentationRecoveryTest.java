package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Replays the first real mini capture through ordinary native tools, without inference or repair authority. */
class MiniTopologyRepresentationRecoveryTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String FIXTURE_SHA256 =
      "f19420d523d26a9ac4720daae79e2ae140bac72e1e194a9dbac34ad45556a170";

  @Test
  void correctedLocalRepresentationIsAcceptedWithoutRejectedProjection() throws Exception {
    var h = new Harness();
    var corrected = corrected(h.initial);
    assertOnlyRepresentationChanged(h.initial, corrected);

    var result = h.capture(corrected);

    assertTrue(result.accepted(), result.toString());
    assertEquals(corrected, h.store.get(h.chat).orElseThrow().authoredDraft());
    assertEquals(corrected, result.draft());
    h.assertSourceAndAttempt(corrected, true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedTopologyCannotBeDemotedWithoutIndependentSemanticProof(boolean reload) throws Exception {
    var h = new Harness();
    h.rejectInitial();
    var originalAttempt = h.artifacts.latest(h.sessions.active(h.chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    var originalPayload = originalAttempt.payload().deepCopy();
    var originalBase = h.store.latestRevision(h.chat);
    if (reload) h.restart();
    var corrected = corrected(h.initial);
    assertOnlyRepresentationChanged(h.initial, corrected);
    assertTrue(RequirementCaptureEditor.initialize(corrected).accepted());

    var result = h.capture(corrected);

    assertFalse(result.accepted(), "Changed interaction scopes require independent semantic proof.");
    assertTrue(result.issues().stream().anyMatch(issue -> "AUTHOR_AMENDMENT_REQUIRED".equals(issue.code())
        && "/capabilities/summarizeParcels".equals(issue.path())), result.toString());
    assertEquals(originalBase, h.store.latestRevision(h.chat));
    assertTrue(h.store.get(h.chat).isEmpty());
    assertNull(result.revision());
    assertNull(result.draft());
    assertEquals(originalPayload, h.artifacts.get(originalAttempt.compilationId(), originalAttempt.reference())
        .orElseThrow().payload());
    h.assertSourceAndAttempt(corrected, false);
  }

  @Test
  void topologyCorrectionCannotDeleteTheActualScriptBehavior() throws Exception {
    var h = new Harness();
    h.rejectInitial();
    var proposed = (ObjectNode) JSON.valueToTree(corrected(h.initial));
    var facts = proposed.putArray("facts");
    for (var fact : corrected(h.initial).facts()) {
      if (!"script-behavior".equals(fact.sourceFactId())) facts.add(JSON.valueToTree(fact));
    }
    var candidate = JSON.treeToValue(proposed, DraftInput.class);

    var result = h.capture(candidate);

    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
        && "/draft/facts/script-behavior".equals(issue.path())), result.toString());
    assertTrue(h.store.latestRevision(h.chat).isEmpty());
    h.assertSourceAndAttempt(candidate, false);
  }

  @Test
  void topologyCorrectionCannotChangeTheTrueInboundRoute() throws Exception {
    var h = new Harness();
    h.rejectInitial();
    var proposed = (ObjectNode) JSON.valueToTree(corrected(h.initial));
    ((ObjectNode) proposed.withArray("capabilities").get(0)).put("path", "/unauthorized-route");
    var candidate = JSON.treeToValue(proposed, DraftInput.class);

    var result = h.capture(candidate);

    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue -> "AUTHOR_AMENDMENT_REQUIRED".equals(issue.code())
        && "/capabilities/entry/path".equals(issue.path())), result.toString());
    assertTrue(h.store.latestRevision(h.chat).isEmpty());
    h.assertSourceAndAttempt(candidate, false);
  }

  private static DraftInput corrected(DraftInput initial) throws Exception {
    var result = (ObjectNode) JSON.valueToTree(initial);
    var flow = (ObjectNode) result.path("flow");
    flow.set("interactions", JSON.createArrayNode().add(JSON.valueToTree(initial.flow().interactions().getFirst())));
    flow.putArray("transitions");
    result.set("capabilities", JSON.createArrayNode().add(JSON.valueToTree(initial.capabilities().getFirst())));
    for (var fact : result.withArray("facts")) {
      if (List.of("script-name", "script-behavior").contains(fact.path("sourceFactId").asText())) {
        ((ObjectNode) fact).putArray("interactionIds").add("entry");
      }
      if ("script-name".equals(fact.path("sourceFactId").asText())) {
        ((ObjectNode) fact.path("elementIntent")).put("ownerInteractionId", "entry");
      }
    }
    return JSON.treeToValue(result, DraftInput.class);
  }

  private static void assertOnlyRepresentationChanged(DraftInput before, DraftInput after) {
    assertEquals(List.of(before.flow().interactions().getFirst()), after.flow().interactions());
    assertTrue(after.flow().transitions().isEmpty());
    assertEquals(List.of(before.capabilities().getFirst()), after.capabilities());
    assertEquals(before.openQuestions(), after.openQuestions());
    assertEquals(before.settings(), after.settings());
    assertEquals(before.facts().size(), after.facts().size());
    for (int index = 0; index < before.facts().size(); index++) {
      var expected = (ObjectNode) JSON.valueToTree(before.facts().get(index));
      String id = expected.path("sourceFactId").asText();
      if (List.of("script-name", "script-behavior").contains(id)) expected.putArray("interactionIds").add("entry");
      if ("script-name".equals(id)) ((ObjectNode) expected.path("elementIntent")).put("ownerInteractionId", "entry");
      assertEquals(expected, JSON.valueToTree(after.facts().get(index)), id);
    }
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static final class Harness {
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, JSON, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final String chat = "mini-topology-recovery-" + UUID.randomUUID();
    final String source;
    final DraftInput initial;
    final ConversationArchive.Document originalSource;
    final ConversationArchive.Input sourceInput;
    RequirementDraftStore store;
    ToolProviderResult provider;

    Harness() throws Exception {
      JsonNode fixture;
      try (var stream = MiniTopologyRepresentationRecoveryTest.class.getResourceAsStream(
          "/recovery/mini-topology-first-capture.json")) {
        assertNotNull(stream);
        byte[] bytes = stream.readAllBytes();
        assertEquals(FIXTURE_SHA256, sha256(bytes));
        fixture = JSON.readTree(bytes);
      }
      source = fixture.path("source").asText();
      assertEquals(fixture.path("origin").path("sourceSha256").asText(),
          sha256(source.getBytes(StandardCharsets.UTF_8)));
      initial = JSON.treeToValue(fixture.path("draft"), DraftInput.class);
      sourceInput = conversations.archive().beginInput(chat, source, null);
      conversations.archive().resolveInput(chat, sourceInput, source);
      originalSource = conversations.archive().read(chat);
      restart();
    }

    void restart() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), JSON);
      var schemas = DeterministicElementSchemaService.createForUnitTests(JSON);
      tools.elementSchemas = schemas;
      store.elementSchemas = schemas;
      tools.conversations = conversations;
      provider = new RequirementCaptureToolProvider(tools, JSON, conversations).provideTools(null);
      store.beginTurn(chat, source);
    }

    RequirementCaptureResult capture(DraftInput draft) throws Exception {
      String encoded = provider.toolExecutorByName("captureRequirementDraft").execute(
          ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name("captureRequirementDraft")
              .arguments(JSON.writeValueAsString(Map.of("draft", draft))).build(), chat);
      return JSON.readValue(encoded, RequirementCaptureResult.class);
    }

    void rejectInitial() throws Exception {
      var result = capture(initial);
      assertFalse(result.accepted());
      assertTrue(result.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_REFERENCE".equals(issue.code())
          && "/draft/facts/script-name/elementIntent".equals(issue.path())), result.toString());
      assertTrue(store.get(chat).isEmpty());
      assertTrue(store.latestRevision(chat).isEmpty());
      assertSourceAndAttempt(initial, false);
    }

    void assertSourceAndAttempt(DraftInput candidate, boolean accepted) {
      var revision = artifacts.latest(sessions.active(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      var attempt = artifacts.payload(revision, RequirementDraftStore.RequirementCaptureAttempt.class);
      assertEquals(accepted, attempt.accepted());
      assertEquals(originalSource.generation(), attempt.sourceGeneration());
      assertEquals(sourceInput.turnId(), attempt.sourceTurnId());
      assertEquals(JSON.valueToTree(candidate), JSON.valueToTree(attempt.arguments()));
      var currentSource = conversations.archive().read(chat);
      assertEquals(originalSource.generation(), currentSource.generation());
      assertEquals(originalSource.activeTurnIds(), currentSource.activeTurnIds());
      var turn = currentSource.activeTurns().getFirst();
      assertEquals(sourceInput.turnId(), turn.id());
      assertEquals(source, turn.rawText());
      assertEquals(source, turn.message().content());
      if (accepted) {
        assertEquals(store.latestRevision(chat).orElseThrow().artifactId(), turn.consumedDraftReference());
      } else {
        assertNull(attempt.acceptedDraftReference());
        assertEquals(originalSource, currentSource);
      }
    }
  }
}
