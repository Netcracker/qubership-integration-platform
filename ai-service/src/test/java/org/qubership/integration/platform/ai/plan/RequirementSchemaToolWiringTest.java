package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProviderRequest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RequirementSchemaToolWiringTest {
  @Test
  void discoveryCanReadTheCurrentNativePropertySchemaThroughItsActualProvider() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(new RequirementDraftStore(), mock(RequirementDraftTool.class), mapper);
    var context = InvocationContext.builder().chatMemoryId("schema-discovery")
        .invocationParameters(new InvocationParameters()).build();
    var request = ToolProviderRequest.builder().invocationContext(context)
        .userMessage(UserMessage.from("Describe the native property contract.")).build();
    var provider = new RequirementCaptureToolProvider(tools, mapper).provideTools(request);
    assertTrue(provider.toolSpecificationByName("describeElementPatchSchema") != null);
    var result = mapper.readTree(provider.toolExecutorByName("describeElementProperty").execute(
        ToolExecutionRequest.builder().id("native-property").name("describeElementProperty")
            .arguments("{\"elementType\":\"log-record\",\"propertyPath\":\"message\"}").build(), "schema-discovery"));
    if (result.isTextual()) result = mapper.readTree(result.textValue());
    assertTrue(result.path("ok").asBoolean(), result.toString());
    assertEquals("message", result.path("data").path("propertyPath").asText());
    assertEquals("string", result.path("data").path("schema").path("type").asText());
  }
}
