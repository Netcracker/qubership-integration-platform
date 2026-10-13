package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import org.junit.jupiter.api.Test;

class StructuredCaptureArgumentsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void checksNestedRequiredFieldsAfterUnwrappingStringifiedCaptures() throws Exception {
    String capture = "{\"operations\":[{\"nodeId\":\"script-1\"}]}";
    for (String value : java.util.List.of(capture, mapper.writeValueAsString(capture))) {
      var result = inspect("{\"capture\":" + value + "}");
      assertEquals("MISSING_FIELD", result.issue().code());
      assertEquals("/capture/operations/0/elementType", result.issue().path());
    }
  }

  @Test
  void requiresAPresentNonNullTypeButAcceptsNullableOptionalFields() {
    var missingType = inspect("""
        {"capture":{"operations":[{"nodeId":"script-1","elementType":null}]}}
        """);
    assertEquals("INVALID_TYPE", missingType.issue().code());
    assertEquals("/capture/operations/0/elementType", missingType.issue().path());
    assertNull(inspect("""
        {"capture":{"operations":[{"nodeId":"script-1","elementType":"script","label":null}]}}
        """).issue());
  }

  @Test
  void stillRejectsUnknownFieldsAndDuplicateKeys() {
    assertEquals("UNEXPECTED_FIELD", inspect("""
        {"capture":{"operations":[{"nodeId":"script-1","elementType":"script","invented":true}]}}
        """).issue().code());
    assertEquals("DUPLICATE_JSON_KEY", inspect("""
        {"capture":{"operations":[],"operations":[]}}
        """).issue().code());
  }

  @Test
  void nullableArraysRetainTheExactFieldPathOfAnInvalidItem() {
    var item = JsonObjectSchema.builder().addProperty("scalarValue",
        JsonAnyOfSchema.builder().anyOf(new JsonStringSchema(), new JsonNullSchema()).build()).build();
    var capture = JsonObjectSchema.builder().addProperty("propertyPatches",
        JsonAnyOfSchema.builder().anyOf(JsonArraySchema.builder().items(item).build(), new JsonNullSchema()).build())
        .build();
    var method = new ToolMethodCreateInfo("capture", "invoker",
        ToolSpecification.builder().name("capture")
            .parameters(JsonObjectSchema.builder().addProperty("capture", capture).required("capture").build())
            .build(), "mapper", ToolMethodCreateInfo.ExecutionModel.BLOCKING,
        ReturnBehavior.TO_LLM, null, null);
    var result = StructuredCaptureArguments.inspect(ToolExecutionRequest.builder().id("call-1").name("capture")
        .arguments("{\"capture\":{\"propertyPatches\":[{\"scalarValue\":false}]}}")
        .build(), method, mapper);
    assertEquals("INVALID_TYPE", result.issue().code());
    assertEquals("/capture/propertyPatches/0/scalarValue", result.issue().path());
  }

  private StructuredCaptureArguments.Result inspect(String arguments) {
    var operation = JsonObjectSchema.builder()
        .addStringProperty("nodeId").addStringProperty("elementType")
        .addProperty("label", JsonAnyOfSchema.builder().anyOf(new JsonStringSchema(), new JsonNullSchema()).build())
        .required("nodeId", "elementType").additionalProperties(false).build();
    var capture = JsonObjectSchema.builder()
        .addProperty("operations", JsonArraySchema.builder().items(operation).build()).build();
    var method = new ToolMethodCreateInfo("capture", "invoker",
        ToolSpecification.builder().name("capture")
            .parameters(JsonObjectSchema.builder().addProperty("capture", capture).required("capture").build())
            .build(), "mapper", ToolMethodCreateInfo.ExecutionModel.BLOCKING,
        ReturnBehavior.TO_LLM, null, null);
    return StructuredCaptureArguments.inspect(ToolExecutionRequest.builder()
        .id("call-1").name("capture").arguments(arguments).build(), method, mapper);
  }
}
