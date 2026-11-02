package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void malformedArgumentsRetainNativeCoordinatesAndContext() {
    var issue = inspect("{\"capture\":{\"items\":[{\"name\":\"ok\",\"broken\":}]}}").issue();
    assertEquals("INVALID_JSON", issue.code());
    assertEquals("/", issue.path());
    assertTrue(issue.message().contains("Unexpected character"), issue.message());
    assertTrue(issue.message().contains("layer=outer"), issue.message());
    assertTrue(issue.message().contains("context=/capture/items/0/broken"), issue.message());
    assertTrue(issue.message().contains("line=1; column=44; charOffset=43"), issue.message());
  }

  @Test
  void decodedNamedArgumentsUseTheirOwnCoordinateSpace() throws Exception {
    String decoded = "{\"items\":[{\"name\":\"ok\",\"broken\":}]}";
    var issue = inspect("{\"capture\":" + mapper.writeValueAsString(decoded) + "}").issue();
    assertEquals("/", issue.path());
    assertTrue(issue.message().contains("layer=decoded named argument /capture"), issue.message());
    assertTrue(issue.message().contains("context=/items/0/broken"), issue.message());
    assertTrue(issue.message().contains("line=1; column=33; charOffset=32"), issue.message());
    assertEquals("INVALID_TYPE", inspect(mapper.writeValueAsString("{\"capture\":{}}")).issue().code(),
        "The compiler capture still rejects a whole-root string envelope.");
  }

  @Test
  void nativeDuplicateTrailingBlankAndUnicodeDetailsRemainSyntaxFailures() {
    var duplicate = inspect("{\"capture\":{\"x\":1,\"x\":2}}").issue();
    assertEquals("DUPLICATE_JSON_KEY", duplicate.code());
    assertEquals("/", duplicate.path());
    assertTrue(duplicate.message().contains("context=/capture/x"), duplicate.message());
    assertTrue(duplicate.message().contains("Duplicate field 'x'"), duplicate.message());
    var trailing = inspect("{\"capture\":{}} []").issue();
    assertEquals("INVALID_JSON", trailing.code());
    assertTrue(trailing.message().contains("Trailing JSON content"), trailing.message());
    assertTrue(trailing.message().contains("charOffset=15"), trailing.message());
    var blank = inspect("  ").issue();
    assertEquals("INVALID_JSON", blank.code());
    assertTrue(blank.message().contains("layer=outer; location unavailable; reason=Empty JSON arguments"));
    assertTrue(!blank.message().contains("charOffset="));
    String raw = "{\"capture\":{\"a/b~\":[\"😀\",{\"pad\":\"" + "ø".repeat(13_000) + "\",\n\"broken\":}]}}";
    var unicode = inspect(raw).issue();
    assertEquals("/", unicode.path());
    assertTrue(unicode.message().contains("context=/capture/a~1b~0/1/broken"), unicode.message());
    assertTrue(unicode.message().contains("line=2; column=10; charOffset=" + raw.indexOf("}]}")), unicode.message());
    assertTrue(unicode.message().contains("byteOffset=-1"), unicode.message());
  }

  @Test
  void restoresOnlyTheSchemaDeclaredEnvelopeWithoutChangingValues() throws Exception {
    var body = JsonObjectSchema.builder().addProperty("items",
        JsonArraySchema.builder().items(new JsonStringSchema()).build()).build();
    var schema = JsonObjectSchema.builder().addProperty("payload", body).additionalProperties(false).build();
    var original = mapper.readTree("{\"payload\":{},\"items\":[\"a=b\",\" value \"]}");
    var restored = StructuredCaptureArguments.restoreNesting(original, schema);
    assertEquals(mapper.readTree("{\"payload\":{\"items\":[\"a=b\",\" value \"]}}"), restored.arguments());
    assertEquals(java.util.Map.of("/items", "/payload/items"), restored.relocations());
    assertEquals(mapper.readTree("{\"payload\":{},\"items\":[\"a=b\",\" value \"]}"), original);
  }

  @Test
  void leavesAmbiguousConflictingAbsentUnknownAndAllowedRootFieldsUntouched() throws Exception {
    var body = JsonObjectSchema.builder().addStringProperty("value").build();
    var closed = JsonObjectSchema.builder().addProperty("payload", body).additionalProperties(false).build();
    var ambiguous = closed.toBuilder().addProperty("other", body).build();
    var allowed = closed.toBuilder().addStringProperty("value").build();
    for (var example : java.util.List.of(
        java.util.Map.entry(closed, "{\"payload\":{\"value\":\"old\"},\"value\":\"new\"}"),
        java.util.Map.entry(closed, "{\"payload\":null,\"value\":\"new\"}"),
        java.util.Map.entry(closed, "{\"value\":\"new\"}"),
        java.util.Map.entry(closed, "{\"payload\":{},\"grant\":true}"),
        java.util.Map.entry(ambiguous, "{\"payload\":{},\"other\":{},\"value\":\"new\"}"),
        java.util.Map.entry(allowed, "{\"payload\":{},\"value\":\"new\"}"),
        java.util.Map.entry(closed.toBuilder().additionalProperties(true).build(), "{\"payload\":{},\"value\":\"new\"}"))) {
      var input = mapper.readTree(example.getValue());
      var restored = StructuredCaptureArguments.restoreNesting(input, example.getKey());
      assertEquals(input, restored.arguments());
      assertEquals(java.util.Map.of(), restored.relocations());
    }
  }

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
