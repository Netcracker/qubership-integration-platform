package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureProviderTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "captureRequirementDraft,draft,facts",
      "updateRequirementDraft,changes,addFacts",
      "updateRequirementDraft,changes,updateFacts"
  })
  void intentSchemaSeparatesNamesAndRuntimeValuesFromBusinessDescriptions(
      String tool, String parameter, String factsField) {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var provider = new RequirementCaptureToolProvider(new RequirementCaptureTools(new RequirementDraftStore(),
        mock(RequirementDraftTool.class), mapper), mapper).provideTools(null);
    var input = assertInstanceOf(JsonObjectSchema.class,
        provider.toolSpecificationByName(tool).parameters().properties().get(parameter));
    var facts = assertInstanceOf(JsonArraySchema.class, input.properties().get(factsField));
    var fact = assertInstanceOf(JsonObjectSchema.class, facts.items());
    var intentValue = assertInstanceOf(JsonAnyOfSchema.class, fact.properties().get("elementIntent"));
    var intent = intentValue.anyOf().stream().filter(JsonObjectSchema.class::isInstance)
        .map(JsonObjectSchema.class::cast).findFirst().orElseThrow();
    var name = assertInstanceOf(JsonAnyOfSchema.class, intent.properties().get("requestedName"));
    assertTrue(name.anyOf().stream().anyMatch(JsonNullSchema.class::isInstance));
    org.junit.jupiter.api.Assertions.assertNotNull(name.description());
    assertTrue(name.description().contains("ANCHOR"), name.description());
    assertTrue(name.description().contains("participant"), name.description());
    var literals = assertInstanceOf(JsonArraySchema.class, intent.properties().get("exactValues"));
    String description = literals.description();
    assertTrue(description.contains("Complete author-supplied runtime keys or scalar values"), description);
    assertTrue(description.contains("complete executable"), description);
    assertTrue(description.contains("literal component or prefix byte-for-byte"), description);
    assertTrue(description.contains("compose separators and runtime expressions outside"), description);
    assertTrue(description.contains("object property assignment or binding"), description);
    assertTrue(description.contains("exact key and any actual fixed scalar separately"), description);
    assertTrue(description.contains("whole key=value clause is a required constant only"), description);
    assertTrue(description.contains("explicitly requests that complete string as a literal"), description);
    assertTrue(description.contains("BEHAVIOR text"), description);
    assertTrue(description.contains("FIELD_MAPPING on a real business transition"), description);
    assertTrue(description.contains("Source occurrence alone"), description);
    assertTrue(description.contains("'a=b'"), description);
  }

  @Test
  void fixedPropertyBindingRetainsSeparateConstantsAndTheCompleteBehaviorThroughNativeCapture() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), mapper);
    tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(mapper);
    var provider = new RequirementCaptureToolProvider(tools, mapper).provideTools(null);
    String behavior = "The Script must output a JSON object with receiptLabel=ready-for-review."
        + " Copy the original id into sourceId and retain the complete original parameters in details.";
    String chat = "fixed-property-binding-" + UUID.randomUUID();
    store.beginTurn(chat, "Receive POST /work. " + behavior);
    var intent = new RequirementElementIntent("reply", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "entry", null, List.of(), List.of("receiptLabel", "ready-for-review"));
    var fact = new FactInput("reply", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        behavior, null, null, intent);
    var input = withNativeAnchors(new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null)), List.of()),
        List.of(fact), List.of(new RequirementCaptureInput.CapabilityInput("entry", "http-trigger",
            RequirementCaptureInput.HttpMode.CUSTOM, RequirementCaptureInput.HttpMethod.POST, "/work", null, null, null)),
        List.of(), new DraftSettings(null, null)));

    var result = mapper.readTree(provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("fixed-property-binding").name("captureRequirementDraft")
            .arguments(mapper.writeValueAsString(Map.of("draft", input))).build(), chat));

    assertTrue(result.path("accepted").asBoolean(), result.toString());
    assertEquals(input, store.get(chat).orElseThrow().authoredDraft());
    assertEquals(fact, store.get(chat).orElseThrow().authoredDraft().facts().stream()
        .filter(value -> value.sourceFactId().equals("reply")).findFirst().orElseThrow());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"a=b", "key with spaces=value", "sourceTask=original taskId"})
  void completeResponseLiteralsAndTheirFullBehaviorSurviveNativeProviderCapture(String literal) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), mapper);
    tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(mapper);
    var provider = new RequirementCaptureToolProvider(tools, mapper).provideTools(null);
    String behavior = "The Script must return exactly \"" + literal + "\". Retain original record identifiers"
        + " in exchange properties and set result.sourceId from the original id before replacing the body.";
    String chat = "complete-literal-" + UUID.randomUUID();
    store.beginTurn(chat, "Receive POST /work. " + behavior);
    var intent = new RequirementElementIntent("reply", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "entry", null, List.of(), List.of(literal));
    var fact = new FactInput("reply", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        behavior, null, null, intent);
    var input = withNativeAnchors(new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null)), List.of()),
        List.of(fact), List.of(new RequirementCaptureInput.CapabilityInput("entry", "http-trigger",
            RequirementCaptureInput.HttpMode.CUSTOM, RequirementCaptureInput.HttpMethod.POST, "/work", null, null, null)),
        List.of(), new DraftSettings(null, null)));

    var result = mapper.readTree(provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("complete-literal").name("captureRequirementDraft")
            .arguments(mapper.writeValueAsString(Map.of("draft", input))).build(), chat));

    assertTrue(result.path("accepted").asBoolean(), result.toString());
    assertEquals(input, store.get(chat).orElseThrow().authoredDraft());
    assertEquals(fact, store.get(chat).orElseThrow().authoredDraft().facts().stream()
        .filter(value -> value.sourceFactId().equals("reply")).findFirst().orElseThrow());
  }

  @Test
  void updateSchemaRequiresScopedAmendmentEvidence() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var provider = new RequirementCaptureToolProvider(new RequirementCaptureTools(new RequirementDraftStore(),
        mock(RequirementDraftTool.class), mapper), mapper).provideTools(null);
    var changes = assertInstanceOf(JsonObjectSchema.class,
        provider.toolSpecificationByName("updateRequirementDraft").parameters().properties().get("changes"));
    assertTrue(changes.required().contains("amendments"));
    var amendments = assertInstanceOf(JsonArraySchema.class, changes.properties().get("amendments"));
    var item = assertInstanceOf(JsonObjectSchema.class, amendments.items());
    assertEquals(java.util.Set.of("path", "sourceTurnId", "quote"), java.util.Set.copyOf(item.required()));
  }

  @Test
  void malformedToolArgumentsRemainIntactWithTheirTypedRejection() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions);
    var provider = new RequirementCaptureToolProvider(
        new RequirementCaptureTools(store, mock(RequirementDraftTool.class), mapper), mapper).provideTools(null);
    String chat = "raw-rejection-" + UUID.randomUUID();
    String arguments = "{\"draft\": {}, \"draft\": {\"sentinel\": \"preserve this entire rejected input\"}}";
    var output = provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("1").name("captureRequirementDraft").arguments(arguments).build(), chat);
    assertEquals("DUPLICATE_JSON_KEY", mapper.readTree(output).path("issues").get(0).path("code").asText());
    var attempt = artifacts.latest(sessions.active(chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
    assertEquals(arguments, attempt.path("arguments").asText());
    assertEquals("DUPLICATE_JSON_KEY", attempt.path("issues").get(0).path("code").asText());
    assertTrue(store.get(chat).isEmpty());
  }

  @Test
  void rootStringSyntaxDetailsRetainTheOriginalLayerAndRawAudit() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions);
    var provider = new RequirementCaptureToolProvider(
        new RequirementCaptureTools(store, mock(RequirementDraftTool.class), mapper), mapper).provideTools(null);
    String decoded = "{\"draft\":{\"facts\":[{\"broken\":}]}}";
    for (boolean rootString : java.util.List.of(false, true)) {
      String chat = "syntax-layer-" + UUID.randomUUID();
      String raw = rootString ? mapper.writeValueAsString(decoded) : decoded;
      var output = mapper.readTree(provider.toolExecutorByName("captureRequirementDraft").execute(
          ToolExecutionRequest.builder().id("syntax-call").name("captureRequirementDraft").arguments(raw).build(), chat));
      var issue = output.path("issues").get(0);
      assertEquals("INVALID_JSON", issue.path("code").asText());
      assertEquals("/", issue.path("path").asText());
      String message = issue.path("message").asText();
      assertTrue(message.contains("layer=" + (rootString ? "decoded rootString argument /" : "outer")), message);
      assertTrue(message.contains("context=/draft/facts/0/broken"), message);
      assertTrue(message.contains("charOffset="), message);
      var attempt = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
      assertEquals(raw, attempt.path("arguments").asText());
      assertEquals(issue, attempt.path("issues").get(0));
      assertTrue(store.get(chat).isEmpty());
    }
  }

  @Test
  void nullableRequiredFieldsWorkThroughTheProvider() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    RequirementCaptureTools tools = new RequirementCaptureTools(store, adapter, mapper);
    ToolProviderResult provider = new RequirementCaptureToolProvider(tools, mapper)
        .provideTools(null);

    JsonObjectSchema parameters = provider.toolSpecificationByName("captureRequirementDraft")
        .parameters();
    assertEquals(List.of("draft"), parameters.required());
    assertEquals(Boolean.FALSE, parameters.additionalProperties());
    JsonObjectSchema draftSchema = assertInstanceOf(JsonObjectSchema.class,
        parameters.properties().get("draft"));
    assertEquals(draftSchema.properties().keySet(),
        new java.util.HashSet<>(draftSchema.required()));
    JsonObjectSchema flow = assertInstanceOf(JsonObjectSchema.class,
        draftSchema.properties().get("flow"));
    JsonArraySchema interactions = assertInstanceOf(JsonArraySchema.class,
        flow.properties().get("interactions"));
    JsonObjectSchema interaction = assertInstanceOf(JsonObjectSchema.class,
        interactions.items());
    JsonAnyOfSchema retry = assertInstanceOf(JsonAnyOfSchema.class,
        interaction.properties().get("retryPolicy"));
    assertTrue(retry.anyOf().stream().anyMatch(JsonNullSchema.class::isInstance));
    JsonArraySchema capabilities = assertInstanceOf(JsonArraySchema.class,
        draftSchema.properties().get("capabilities"));
    JsonObjectSchema capability = assertInstanceOf(JsonObjectSchema.class,
        capabilities.items());
    assertTrue(capability.properties().get("httpMode").description()
        .contains("null for every other capability"));
    JsonArraySchema facts = assertInstanceOf(JsonArraySchema.class, draftSchema.properties().get("facts"));
    JsonObjectSchema fact = assertInstanceOf(JsonObjectSchema.class, facts.items());
    JsonAnyOfSchema intentValue = assertInstanceOf(JsonAnyOfSchema.class, fact.properties().get("elementIntent"));
    JsonObjectSchema intent = intentValue.anyOf().stream().filter(JsonObjectSchema.class::isInstance)
        .map(JsonObjectSchema.class::cast).findFirst().orElseThrow();
    assertTrue(intent.properties().get("successorElementRef").description().contains("A -> B"));
    assertTrue(intent.properties().get("predecessorElementRef").description().contains("B -> A"));
    var types = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonEnumSchema.class,
        intent.properties().get("elementType")).enumValues();
    assertTrue(types.contains("split-async-2"));
    assertTrue(types.contains("chain"));
    assertTrue(!types.contains("split-async"));

    DraftInput input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks",
            null, null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL,
            Polarity.POSITIVE, "Receive a task.")), List.of(), List.of(),
        new DraftSettings(false, null));
    String conversationId = "capture-provider-" + UUID.randomUUID();
    String output = provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("1").name("captureRequirementDraft")
            .arguments(mapper.writeValueAsString(Map.of("draft", input))).build(),
        conversationId);
    assertTrue(mapper.readTree(output).get("accepted").asBoolean());
    assertEquals(input, store.get(conversationId).orElseThrow().authoredDraft());
  }

  @Test
  void duplicateKeyIsRejectedBeforeStateChanges() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementCaptureTools tools = new RequirementCaptureTools(
        store, mock(RequirementDraftTool.class), mapper);
    ToolProviderResult provider = new RequirementCaptureToolProvider(tools, mapper)
        .provideTools(null);
    String conversationId = "capture-duplicate-" + UUID.randomUUID();
    String output = provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("1").name("captureRequirementDraft")
            .arguments("{\"draft\":{},\"draft\":{}}").build(), conversationId);
    assertEquals("DUPLICATE_JSON_KEY", mapper.readTree(output).get("issues").get(0)
        .get("code").asText());
    assertTrue(store.get(conversationId).isEmpty());

    String repeated = provider.toolExecutorByName("captureRequirementDraft").execute(
        ToolExecutionRequest.builder().id("2").name("captureRequirementDraft")
            .arguments("{\"draft\":{},\"draft\":{}}").build(), conversationId);
    assertEquals("REPAIR_CAPTURE", mapper.readTree(repeated).get("nextAction").asText());
    assertEquals("DUPLICATE_JSON_KEY", mapper.readTree(repeated).get("issues").get(0)
        .get("code").asText());
    assertTrue(store.get(conversationId).isEmpty());
  }
}
