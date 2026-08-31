package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * The generated LangChain4j mapper deserializes {@link MappingIntent} with the Quarkus tool
 * ObjectMapper (field visibility only). A plain {@code new ObjectMapper()} round trip is not
 * enough: live capture used to throw {@code InvalidDefinitionException} for creator property
 * {@code sourceRef} before {@link RequirementBriefTool} ran.
 */
@QuarkusTest
class RequirementBriefToolArgumentMapperTest {

  private static final String METHOD = "captureRequirementBrief";

  @Inject ObjectMapper objectMapper;

  @AfterEach
  void clearConversation() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void quarkusToolMapperBindsMappingIntent() throws Exception {
    MappingIntent intent =
        toolMapper()
            .readValue(
                """
                {
                  "mappingIntentId": "map-request",
                  "sourceRef": "trigger-onTaskStart",
                  "sourcePort": "OUTPUT",
                  "targetRef": "call-salesforce-createTask",
                  "targetPort": "REQUEST",
                  "rules": [{"sourcePath": "name", "targetPath": "Subject"}]
                }
                """,
                MappingIntent.class);

    assertTrue(intent.sourceRef().equals("trigger-onTaskStart"), intent.sourceRef());
  }

  @Test
  void generatedMapperAcceptsDuplicateCreatorProperties() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-mapping-intent-dup");
    String result =
        execute(
            """
            {"capture": {
              "goal": "OM to Salesforce WFM",
              "summary": "Map request fields",
              "mappingIntents": [{
                "mappingIntentId": "map-request",
                "sourceRef": "trigger-onTaskStart",
                "sourceRef": "trigger-onTaskStart",
                "sourcePort": "OUTPUT",
                "targetRef": "call-salesforce-createTask",
                "targetPort": "REQUEST",
                "rules": [{"sourcePath": "name", "targetPath": "Subject"}]
              }]
            }}
            """);
    assertTrue(result.contains("Requirement brief captured"), result);
  }

  @Test
  void generatedMapperAcceptsMappingIntents() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-mapping-intent-mapper");
    String result =
        execute(
            """
            {"capture": {
              "goal": "OM to Salesforce WFM",
              "summary": "Map request fields",
              "mappingIntents": [{
                "mappingIntentId": "map-request",
                "sourceRef": "trigger-onTaskStart",
                "sourcePort": "OUTPUT",
                "targetRef": "call-salesforce-createTask",
                "targetPort": "REQUEST",
                "fromIntentRef": "trigger-onTaskStart",
                "toIntentRef": "call-salesforce-createTask",
                "stage": "INITIALIZATION",
                "rules": [{"sourcePath": "name", "targetPath": "Subject", "expression": null}]
              }]
            }}
            """);
    assertTrue(result.contains("Requirement brief captured"), result);
  }

  private ObjectMapper toolMapper() {
    return objectMapper
        .copy()
        .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
  }

  private String execute(String arguments) {
    ToolMethodCreateInfo info = createInfo();
    RequirementBriefTool tool =
        new RequirementBriefTool(
            new CaptureSession(),
            new ObjectMapper(),
            new CaptureAttemptFeedbackStore(),
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
    QuarkusToolExecutor executor =
        new QuarkusToolExecutor(
            new QuarkusToolExecutor.Context(
                tool,
                info.invokerClassName(),
                info.methodName(),
                info.argumentMapperClassName(),
                info.executionModel(),
                info.returnBehavior(),
                false,
                info));
    return executor.execute(
        ToolExecutionRequest.builder()
            .id("call-1")
            .name(info.toolSpecification().name())
            .arguments(arguments)
            .build(),
        "conv-mapping-intent-mapper");
  }

  private static ToolMethodCreateInfo createInfo() {
    List<ToolMethodCreateInfo> infos =
        ToolsRecorder.getMetadata().get(RequirementBriefTool.class.getName());
    if (infos == null) {
      throw new IllegalStateException(
          "No generated tool metadata for " + RequirementBriefTool.class.getName());
    }
    return infos.stream()
        .filter(info -> METHOD.equals(info.methodName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No generated tool metadata for " + METHOD));
  }
}
