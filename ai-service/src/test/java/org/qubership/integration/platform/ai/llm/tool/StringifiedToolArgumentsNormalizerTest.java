package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringifiedToolArgumentsNormalizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StringifiedToolArgumentsNormalizer normalizer =
      new StringifiedToolArgumentsNormalizer(objectMapper);

  @Test
  void unwrapsOneJsonLayerForObjectAndArrayParameters() throws Exception {
    ToolExecutionRequest request =
        request("{\"object\":\"{\\\"id\\\":7}\",\"array\":\"[1,2]\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "object", JsonObjectSchema.builder().build(),
                    "array", JsonArraySchema.builder().build())));

    JsonNode arguments = objectMapper.readTree(normalized.arguments());
    assertEquals(7, arguments.get("object").get("id").intValue());
    assertEquals(2, arguments.get("array").size());
  }

  @Test
  void leavesDeclaredStringsAndMalformedJsonUntouched() {
    ToolExecutionRequest request =
        request("{\"script\":\"{\\\"id\\\":7}\",\"capture\":\"{broken\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "script", JsonStringSchema.builder().build(),
                    "capture", JsonObjectSchema.builder().build())));

    assertEquals(request, normalized);
  }

  @Test
  void leavesAmbiguousStringOrObjectParametersUntouched() {
    ToolExecutionRequest request = request("{\"value\":\"{\\\"id\\\":7}\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "value",
                    JsonAnyOfSchema.builder()
                        .anyOf(
                            JsonStringSchema.builder().build(),
                            JsonObjectSchema.builder().build(),
                            new JsonNullSchema())
                        .build())));

    assertEquals(request, normalized);
  }

  private static ToolExecutionRequest request(String arguments) {
    return ToolExecutionRequest.builder().id("call-1").name("capture").arguments(arguments).build();
  }

  private static ToolMethodCreateInfo method(Map<String, JsonSchemaElement> properties) {
    return new ToolMethodCreateInfo(
        "capture",
        "invoker",
        ToolSpecification.builder()
            .name("capture")
            .parameters(JsonObjectSchema.builder().addProperties(properties).build())
            .build(),
        "mapper",
        ToolMethodCreateInfo.ExecutionModel.BLOCKING,
        ReturnBehavior.TO_LLM,
        null,
        null);
  }
}
