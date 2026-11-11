package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding.NativeBodyKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues;

class RequirementBodyCaptureProviderTest {
  @Test
  void serverClassifiesAndPersistsBehaviorWithoutAModelBinding() throws Exception {
    var fixture = fixture("Receive POST /work and return a normalized result from the local processing step.");
    com.fasterxml.jackson.databind.node.ObjectNode payload = fixture.mapper.valueToTree(input(null, null));
    ((com.fasterxml.jackson.databind.node.ObjectNode) payload.withArray("facts").get(0).path("elementIntent"))
        .putArray("requestedProperties");
    var initial = fixture.mapper.treeToValue(payload, DraftInput.class);

    var accepted = fixture.capture(initial);

    assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
    var binding = fixture.store.get(fixture.chat).orElseThrow().authoredDraft().facts().getFirst()
        .elementIntent().bodyBinding();
    assertAll(
        () -> assertEquals(Authority.GENERATED_CANDIDATE, binding.authority()),
        () -> assertEquals(fixture.source().generation(), binding.sourceGeneration()),
        () -> assertEquals(fixture.source().inputs().getFirst().sourceTurnId(), binding.sourceTurnId()),
        () -> assertTrue(org.qubership.integration.platform.ai.plan.mapping.ScriptExecutionAuthority
            .declaredGeneratedBehavior(fixture.brief(), "prepare").isPresent()));
    var reloaded = new RequirementDraftStore(fixture.artifacts, fixture.sessions, fixture.conversations);
    assertEquals(binding, reloaded.get(fixture.chat).orElseThrow().authoredDraft().facts().getFirst()
        .elementIntent().bodyBinding());
  }

  @Test
  void malformedSubmittedBindingStillFailsTheToolSchema() throws Exception {
    var fixture = fixture("Receive POST /work. Use prepare.script: `return 1`");
    var malformed = new RequirementBodyBinding(Authority.VERBATIM_SOURCE_BODY, "prepare", "script", null,
        "generation", "turn", 0, 1, Encoding.RAW);
    var result = fixture.capture(input("return 1", malformed));
    assertFalse(result.path("accepted").asBoolean(), result.toString());
    assertTrue(result.path("issues").toString().contains("INVALID_ENUM"), result.toString());
    assertTrue(fixture.store.get(fixture.chat).isEmpty());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"omitted", "forged"})
  void updatesPersistCanonicalBindingsAndKeepTheSubmittedUpdateEvidence(String metadata) throws Exception {
    var fixture = fixture("Receive POST /work. Generate a local Groovy script returning the requested result.");
    var initial = input("Groovy", null);
    assertTrue(fixture.capture(initial).path("accepted").asBoolean());
    var canonical = fixture.store.get(fixture.chat).orElseThrow().authoredDraft().facts().getFirst()
        .elementIntent().bodyBinding();
    var submittedBinding = "omitted".equals(metadata) ? null : new RequirementBodyBinding(
        Authority.VERBATIM_SOURCE_BODY, "foreign-owner", "script", NativeBodyKind.GROOVY_SCRIPT,
        "foreign-generation", "foreign-turn", 0, 1, Encoding.RAW);
    var fact = input("Groovy", submittedBinding).facts().getFirst();
    var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(fact), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());

    var result = fixture.call("updateRequirementDraft", "changes", changes);

    assertTrue(result.path("accepted").asBoolean(), result.toString());
    assertEquals(canonical, fixture.store.get(fixture.chat).orElseThrow().authoredDraft().facts().getFirst()
        .elementIntent().bodyBinding());
    org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.assertAuthoredContentEquals(
        initial, fixture.store.get(fixture.chat).orElseThrow().authoredDraft());
    var attempt = fixture.artifacts.latest(fixture.sessions.active(fixture.chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
    assertAll(
        () -> assertEquals("updateRequirementDraft", attempt.path("toolName").asText()),
        () -> assertEquals(fixture.mapper.valueToTree(changes), attempt.path("arguments")));
  }

  @Test
  void serverClassifiesLegacyHintsWithoutOverwritingValidatedCode() throws Exception {
    var fixture = fixture("Receive POST /work. Create a local Groovy script producing approved result.");
    var input = input("Groovy", null);
    var accepted = fixture.capture(input);
    assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
    var binding = RequirementBodySources.resolve(input.facts().getFirst().elementIntent(),
        List.of(input.facts().getFirst().elementIntent()), fixture.source()).binding();
    assertEquals(Authority.GENERATED_CANDIDATE, binding.authority());
    var corrected = input("Groovy", binding);
    assertTrue(fixture.capture(corrected).path("accepted").asBoolean());
    var brief = fixture.brief();
    String body = "exchange.in.body = 'approved result'";
    var shell = graph(null);
    assertEquals(shell, RequestedElementValues.seedBodies(shell, brief.facts()), "Hints do not fill script shells.");
    var generated = graph(body);
    var projected = RequestedElementValues.apply(generated, brief.facts());
    assertEquals(body, property(projected));
    assertTrue(CompilerQualityValidator.validateApprovedValues(projected, brief).isEmpty());
    var attempts = fixture.artifacts.history(fixture.sessions.active(fixture.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    assertEquals(2, attempts.size());
    assertTrue(attempts.getFirst().payload().path("arguments").toString().contains("Groovy"));
    assertTrue(attempts.getFirst().payload().path("accepted").asBoolean());
    assertTrue(attempts.getLast().payload().path("accepted").asBoolean());
    assertEquals(binding, fixture.store.get(fixture.chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent().bodyBinding());
  }

  @Test
  void literalSourceCannotBeDowngradedOmittedOrTrimmedAndIsSeededBeforeQuality() throws Exception {
    String body = "  exchange.in.body = 'approved result'  \n\n";
    var fixture = fixture("Receive POST /work. Use the exact script for prepare:\n```groovy\n" + body + "```\n");
    var initial = input(body, null);
    var intent = initial.facts().getFirst().elementIntent();
    var expected = RequirementBodySources.resolve(intent, List.of(intent), fixture.source()).binding();
    assertEquals(Authority.VERBATIM_SOURCE_BODY, expected.authority());
    var trimmed = fixture.capture(input(body.strip(), null));
    assertFalse(trimmed.path("accepted").asBoolean(), trimmed.toString());
    assertTrue(trimmed.path("issues").toString().contains("BODY_SOURCE_MISMATCH"), trimmed.toString());
    assertTrue(fixture.capture(initial).path("accepted").asBoolean());
    var generated = new RequirementBodyBinding(Authority.GENERATED_CANDIDATE, "prepare", "script",
        NativeBodyKind.GROOVY_SCRIPT, expected.sourceGeneration(), expected.sourceTurnId(), null, null, null);
    assertTrue(fixture.capture(input(body, generated)).path("accepted").asBoolean());
    assertEquals(Authority.VERBATIM_SOURCE_BODY, fixture.store.get(fixture.chat).orElseThrow()
        .authoredDraft().facts().getFirst().elementIntent().bodyBinding().authority());
    assertFalse(fixture.capture(input(body.strip(), expected)).path("accepted").asBoolean());
    assertTrue(fixture.capture(input(body, expected)).path("accepted").asBoolean());
    var brief = fixture.brief();
    var seeded = RequestedElementValues.seedBodies(graph(null), brief.facts());
    assertEquals(body, property(seeded));
    assertEquals(seeded, RequestedElementValues.apply(seeded, brief.facts()));
    assertTrue(CompilerQualityValidator.validateApprovedValues(seeded, brief).isEmpty());
    var changed = graph(body.strip());
    assertThrows(IllegalArgumentException.class, () -> RequestedElementValues.apply(changed, brief.facts()));
    assertFalse(CompilerQualityValidator.validateApprovedValues(changed, brief).isEmpty());
    assertEquals(body.strip(), property(changed), "A refusal cannot rewrite an already validated body.");
  }

  @Test
  void serverReplacesStaleForgedForeignAndPartialSpansFromRetainedSource() throws Exception {
    var fixture = fixture("Receive POST /work. 😀 prepare.script:\n```groovy\nreturn 'author 😀'\n```\n");
    var initial = input("return 'author 😀'\n", null);
    var intent = initial.facts().getFirst().elementIntent();
    var expected = RequirementBodySources.resolve(intent, List.of(intent), fixture.source()).binding();
    for (var forged : List.of(
        new RequirementBodyBinding(expected.authority(), "foreign", "script", expected.bodyKind(), expected.sourceGeneration(), expected.sourceTurnId(), expected.startOffset(), expected.endOffset(), expected.encoding()),
        new RequirementBodyBinding(expected.authority(), "prepare", "foreign", expected.bodyKind(), expected.sourceGeneration(), expected.sourceTurnId(), expected.startOffset(), expected.endOffset(), expected.encoding()),
        new RequirementBodyBinding(expected.authority(), "prepare", "script", expected.bodyKind(), "stale", expected.sourceTurnId(), expected.startOffset(), expected.endOffset(), expected.encoding()),
        new RequirementBodyBinding(expected.authority(), "prepare", "script", expected.bodyKind(), expected.sourceGeneration(), "foreign-turn", expected.startOffset(), expected.endOffset(), expected.encoding()),
        new RequirementBodyBinding(expected.authority(), "prepare", "script", expected.bodyKind(), expected.sourceGeneration(), expected.sourceTurnId(), expected.startOffset()+1, expected.endOffset(), expected.encoding()),
        new RequirementBodyBinding(expected.authority(), "prepare", "script", expected.bodyKind(), expected.sourceGeneration(), expected.sourceTurnId(), expected.startOffset(), expected.endOffset(), Encoding.JSON_STRING),
        new RequirementBodyBinding(expected.authority(), "prepare", "script", expected.bodyKind(), expected.sourceGeneration(), expected.sourceTurnId(), expected.startOffset()-1, expected.endOffset()-1, expected.encoding()))) {
      var accepted = fixture.capture(input("return 'author 😀'\n", forged));
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      assertEquals(expected, fixture.store.get(fixture.chat).orElseThrow().authoredDraft()
          .facts().getFirst().elementIntent().bodyBinding());
    }
    assertTrue(fixture.capture(input("return 'author 😀'\n", expected)).path("accepted").asBoolean());
  }

  static DraftInput input(String body, RequirementBodyBinding binding) {
    var intent = new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "entry", null, null, List.of(new PlanProperty("script", body)), List.of(), binding);
    return withNativeAnchors(new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /work", null, null, null)), List.of()),
        List.of(new FactInput("prepare-behavior", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Use the local script to produce the requested result.", null, null, intent)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/work", null, null, null)),
        List.of(), new DraftSettings(null, null)));
  }

  static ChainPlanGraph graph(String body) {
    return new ChainPlanGraph("1.0", new ChainSection("Example", ""), List.of(
        new ChainPlanNode("prepare", "script", "Prepare", null, null,
            body == null ? List.of() : List.of(new PlanProperty("script", body))),
        new ChainPlanNode("entry", "http-trigger", "Entry", null, null,
            List.of(new PlanProperty("httpMethodRestrict", "POST"), new PlanProperty("contextPath", "/work")))), List.of());
  }

  static String property(ChainPlanGraph graph) {
    return graph.nodes().getFirst().properties().stream().filter(value -> "script".equals(value.key()))
        .map(PlanProperty::value).findFirst().orElse(null);
  }

  private static Fixture fixture(String source) {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var conversations = new ConversationService();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, java.time.Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), mapper);
    tools.conversations = conversations;
    tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(mapper);
    String chat = "native-body-" + UUID.randomUUID();
    var turn = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, turn, source);
    store.beginTurn(chat);
    return new Fixture(mapper, store, artifacts, sessions, conversations, tools, chat);
  }

  private record Fixture(ObjectMapper mapper, RequirementDraftStore store, CompilationArtifacts artifacts,
      CompilationSessions sessions, ConversationService conversations, RequirementCaptureTools tools, String chat) {
    com.fasterxml.jackson.databind.JsonNode capture(DraftInput input) throws Exception {
      return call("captureRequirementDraft", "draft", input);
    }
    com.fasterxml.jackson.databind.JsonNode call(String name, String parameter, Object value) throws Exception {
      var provider = new RequirementCaptureToolProvider(tools, mapper).provideTools(null);
      return mapper.readTree(provider.toolExecutorByName(name).execute(ToolExecutionRequest.builder()
          .id(UUID.randomUUID().toString()).name(name)
          .arguments(mapper.writeValueAsString(Map.of(parameter, value))).build(), chat));
    }
    AuthorSourceEvidence.Packet source() { return AuthorSourceEvidence.packet(conversations.archive().read(chat)); }
    RequirementBrief brief() {
      return new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "")
          .withFacts(RequirementCaptureProjection.facts(store.get(chat).orElseThrow().authoredDraft()));
    }
  }
}
