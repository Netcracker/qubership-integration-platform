package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.CompilerSkillAgent;

/** The real compiler agent dispatches schema tools before the scripted provider inspects its next request. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class CompilerSchemaDetailProviderTest {
  private static final String OWNER = "cip-service-call-generator";
  @Inject CompilerSkillAgent agent;
  @Inject ObjectMapper json;
  @Inject AppConfig config;
  @Inject CaptureAttemptFeedbackStore feedback;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @ValueSource(ints = {0, 1600})
  void actualNextRequestRetainsNativeDetailsCurrentSourceAndNativeRefusal(int unicodeChars) throws Exception {
    assertEquals(8000, ConfigProvider.getConfig().getValue(
        "quarkus.langchain4j.chat-memory.token-window.max-tokens", Integer.class));
    String chat = "schema-details-" + UUID.randomUUID();
    String source = "Preserve the original workflow and reference values. Read service-call response-handler"
        + " constraints and the complete HTTP-method contract. Do not infer a branch or invent configuration. "
        + "ø".repeat(unicodeChars);
    String conflict = "{\"patch\":{\"patchId\":\"conflict\",\"ownerCapabilityId\":\"" + OWNER
        + "\",\"notApplicable\":true,\"nodePatches\":[],\"edgePatches\":[],\"propertyPatches\":["
        + "{\"operation\":\"UPDATE\",\"targetNodeId\":\"call\",\"key\":\"retryCount\","
        + "\"value\":{},\"scalarValue\":\"2\"}],\"chainPatches\":[],\"usedKnowledgeRefs\":[]}}";
    var script = new ScriptedStreamingChatModel(
        new Turn("read-native-details", request -> assertCurrentSource(request, source), handler ->
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(
                ToolExecutionRequest.builder().id("after-detail").name("describeElementProperty")
                    .arguments("{\"elementType\":\"service-call\",\"propertyPath\":\"after\"}").build(),
                ToolExecutionRequest.builder().id("method-detail").name("describeElementProperty")
                    .arguments("{\"elementType\":\"http-trigger\",\"propertyPath\":\"httpMethodRestrict\"}").build()))
                .build()).build())),
        Turn.tool("native-conflict", request -> {
          assertCurrentSource(request, source);
          assertDetails(request);
        }, "captureGraphPatch", conflict),
        Turn.text("retained-native-refusal", request -> {
          assertCurrentSource(request, source);
          assertDetails(request);
          var nativeFailure = feedback.lastPatchFailure(chat, OWNER).orElseThrow();
          assertTrue(nativeFailure.outerAllowed());
          assertFalse(nativeFailure.noProgress());
          assertTrue(nativeFailure.summary().contains("notApplicable=true requires empty"));
          assertEquals(json.valueToTree(nativeFailure.summary()).toString(), result(request, "native-conflict").text());
        }, "The native refusal remains unresolved; no graph correction is claimed."));
    var realBoundary = new RateLimitStreamingChatModel(script, config,
        seconds -> { throw new AssertionError("Unexpected provider retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    doAnswer(call -> {
      MDC.put(CompilerSkillMdc.CAPABILITY_ID, OWNER);
      realBoundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    try (var ignored = ToolSession.open(chat)) {
      MDC.put(CompilerSkillMdc.CAPABILITY_ID, OWNER);
      feedback.beginScope(chat, OWNER, Map.of("source", source));
      agent.chat(chat, source).collect().asList().await().atMost(Duration.ofSeconds(30));
      script.assertComplete();
      assertEquals(3, script.requests().size());
      String destination = System.getProperty("qip.test.schema-detail-evidence");
      if (destination != null) {
        Path output = Path.of(destination).resolve("provider-next-" + unicodeChars + ".json");
        Files.createDirectories(output.getParent());
        json.writeValue(output.toFile(), Map.of("scope", "Actual CompilerSkillAgent and native tools; scripted provider",
            "source", source, "memoryTokens", 8000, "requests", script.requests().stream()
                .map(request -> Map.of("messages", request.messages().toString(), "parameters", request.parameters().toString()))
                .toList(), "nativeFeedback", feedback.lastPatchFailure(chat, OWNER).orElseThrow(),
            "modelCorrectionClaimed", false, "nativeLimitsChanged", false));
      }
    } finally {
      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
      feedback.clearPatch(chat, OWNER);
    }
  }

  private void assertDetails(ChatRequest request) {
    JsonNode after = payload(result(request, "after-detail").text()).at("/data/schema");
    var branches = after.at("/items/$refTarget/schema/$refTarget/schema/oneOf");
    assertEquals(3, branches.size(), after.toString());
    assertEquals("script", branches.get(2).at("/properties/type/const").asText());
    assertEquals("type", branches.get(2).path("required").get(0).asText());
    assertEquals("groovy", branches.get(2).at("/allOf/0/$refTarget/schema/properties/exportFileExtension/const").asText());
    JsonNode methods = payload(result(request, "method-detail").text()).at("/data/schema");
    var expected = json.valueToTree(List.of("POST", "GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
    assertTrue(methods.findValues("enum").contains(expected), methods.toString());
    assertTrue(methods.findValues("required").stream().anyMatch(keys -> keys.toString().contains("httpMethods")));
  }

  private JsonNode payload(String value) {
    try {
      JsonNode node = json.readTree(value);
      return node.isTextual() ? json.readTree(node.textValue()) : node;
    } catch (Exception failure) {
      throw new AssertionError("Native tool result is not JSON", failure);
    }
  }

  private static ToolExecutionResultMessage result(ChatRequest request, String id) {
    return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).filter(message -> id.equals(message.id())).findFirst().orElseThrow();
  }

  private static void assertCurrentSource(ChatRequest request, String source) {
    assertTrue(request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .anyMatch(message -> source.equals(message.singleText())), "Current source is absent from the actual provider request");
  }
}
