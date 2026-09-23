package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureProviderTest {

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
    assertEquals("STOP", mapper.readTree(repeated).get("nextAction").asText());
    assertTrue(mapper.readTree(repeated).get("issues").toString()
        .contains("REPEATED_REJECTION"));
  }
}
