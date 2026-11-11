package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;
import org.qubership.integration.platform.ai.plan.RequirementCaptureSchemas;

@QuarkusTest
class ScriptBodyRepairCaptureSchemaTest {

  @InjectMock ArtifactBlobStore blobs;

  @BeforeEach
  void useLocalArtifactStorage() {
    var local = new InMemoryArtifactBlobStore();
    org.mockito.Mockito.doAnswer(call -> { local.put(call.getArgument(0), call.getArgument(1)); return null; })
        .when(blobs).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.when(blobs.get(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> local.get(call.getArgument(0)));
    org.mockito.Mockito.when(blobs.getVersioned(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> local.getVersioned(call.getArgument(0)));
    org.mockito.Mockito.doAnswer(call -> {
      local.putIfVersion(call.getArgument(0), call.getArgument(1), call.getArgument(2)); return null;
    }).when(blobs).putIfVersion(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.nullable(String.class));
  }

  @Inject ScriptBodyRepairTool bean;
  @Inject QuarkusToolExecutorFactory executorFactory;

  @AfterEach
  void cleanup() {
    ToolSession.clear();
  }

  @Test
  void generatedSchemaRequiresBodiesAndKeepsMappingCoverageOptional() throws Exception {
    var method = info();
    var parameters = RequirementCaptureSchemas.specification(method.toolSpecification()).parameters();
    var capture = (JsonObjectSchema) parameters.properties().get("capture");
    var entry = (JsonObjectSchema) ((JsonArraySchema) capture.properties().get("scripts")).items();
    assertTrue(capture.required().contains("scripts"));
    assertTrue(entry.required().containsAll(List.of("targetNodeId", "script")));
    assertFalse(entry.required().contains("mappingCoverage"));

    var mapper = new ObjectMapper();
    String script = "exchange.in.body = groovy.json.JsonOutput.toJson([message: 'Hello'])\nreturn exchange.in.body";
    String arguments = mapper.writeValueAsString(Map.of("capture", Map.of(
        "patchId", "script-body", "scripts", List.of(Map.of("targetNodeId", "script-1", "script", script)))));
    var inspected = StructuredCaptureArguments.inspect(request(arguments), method, mapper);
    assertNull(inspected.issue());
    assertEquals(script, mapper.readTree(inspected.request().arguments())
        .at("/capture/scripts/0/script").textValue());
  }

  @Test
  void generatedBoundaryRejectsTheRecordedIdOnlyCaptureBeforeBinding() {
    ToolSession.bind("script-schema-test");
    var method = info();
    var executor = executorFactory.create(new QuarkusToolExecutor.Context(
        bean, method.invokerClassName(), method.methodName(), method.argumentMapperClassName(),
        method.executionModel(), method.returnBehavior(), false, method));
    String result = executor.execute(request("""
        {"capture":{"patchId":"script-body","scripts":[{"targetNodeId":"get-context-headers"}]}}
        """), "script-schema-test");
    assertTrue(result.contains("MISSING_FIELD /capture/scripts/0/script"), result);
  }

  private static ToolExecutionRequest request(String arguments) {
    return ToolExecutionRequest.builder().id("call-1").name("repairScriptBodies").arguments(arguments).build();
  }

  private static ToolMethodCreateInfo info() {
    return ToolsRecorder.getMetadata().get(ScriptBodyRepairTool.class.getName()).stream()
        .filter(method -> "repairScriptBodies".equals(method.methodName())).findFirst().orElseThrow();
  }
}
