package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;

@QuarkusTest
class HttpTriggerCaptureSchemaTest {

  private static final String CONVERSATION = "http-capture-test";

  @Inject HttpTriggerCaptureTool bean;
  @Inject QuarkusToolExecutorFactory executorFactory;
  @Inject CaptureSession captureSession;

  @AfterEach
  void cleanup() {
    HttpTriggerCaptureSession.unbind(CONVERSATION);
    captureSession.clear(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION));
    ToolSession.clear();
  }

  @Test
  void generatedSchemaKeepsEndpointPropertiesServerOwned() {
    Map<String, JsonSchemaElement> fields = new LinkedHashMap<>();
    collect(info().toolSpecification().parameters(), fields);

    for (String field : List.of("endpoints", "roleId", "semanticNodeId", "externalRoute")) {
      assertTrue(fields.containsKey(field), field);
    }
    assertTrue(fields.get("externalRoute") instanceof JsonBooleanSchema);
    for (String serverOwned : List.of("contextPath", "httpMethodRestrict", "properties",
        "elementType", "sourceRequirementFactIds", "knowledgeCitations")) {
      assertFalse(fields.containsKey(serverOwned), serverOwned);
    }
  }

  @Test
  void generatedBoundaryRejectsWrongBooleanAndUnknownFields() {
    HttpTriggerCaptureAdapterTest.binding();
    ToolSession.bind(CONVERSATION);

    String wrongType = execute("""
        {"capture":{"endpoints":[{"roleId":"http-entry",
          "semanticNodeId":"http-trigger-1","externalRoute":"false"}]}}
        """);
    assertTrue(wrongType.contains("INVALID_TYPE"), wrongType);
    assertTrue(execute("""
        {"capture":{"endpoints":[],"properties":[]}}
        """).contains("UNEXPECTED_FIELD"));
    assertTrue(execute("""
        {"capture":{"endpoints":[],"endpoints":[]}}
        """).contains("DUPLICATE_JSON_KEY"));
  }

  @Test
  void generatedToolPublishesApprovedEndpointProperties() {
    HttpTriggerCaptureAdapterTest.binding();
    ToolSession.bind(CONVERSATION);
    try {
      execute("""
          {"capture":{"endpoints":[{"roleId":"http-entry",
            "semanticNodeId":"http-trigger-1","externalRoute":false}]}}
          """);
    } catch (RuntimeException acceptedTurnStop) {
      // A successful capture stops the streaming tool loop immediately.
    }

    assertTrue(captureSession.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION)));
  }

  private String execute(String arguments) {
    ToolMethodCreateInfo method = info();
    QuarkusToolExecutor executor = executorFactory.create(new QuarkusToolExecutor.Context(
        bean, method.invokerClassName(), method.methodName(),
        method.argumentMapperClassName(), method.executionModel(), method.returnBehavior(),
        false, method));
    return executor.execute(ToolExecutionRequest.builder().id("call-1")
        .name(method.toolSpecification().name()).arguments(arguments).build(), CONVERSATION);
  }

  private static ToolMethodCreateInfo info() {
    return ToolsRecorder.getMetadata().get(HttpTriggerCaptureTool.class.getName()).stream()
        .filter(method -> "captureHttpTriggers".equals(method.methodName()))
        .findFirst().orElseThrow();
  }

  private static void collect(
      JsonSchemaElement element, Map<String, JsonSchemaElement> fields) {
    if (element instanceof JsonObjectSchema object) {
      object.properties().forEach((name, child) -> {
        fields.put(name, child);
        collect(child, fields);
      });
    } else if (element instanceof JsonArraySchema array) {
      collect(array.items(), fields);
    }
  }
}
