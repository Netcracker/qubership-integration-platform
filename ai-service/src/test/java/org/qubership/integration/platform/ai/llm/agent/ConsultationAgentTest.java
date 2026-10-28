package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DocumentationTools;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class ConsultationAgentTest {
  @Inject ConsultationAgent agent;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock DocumentationTools documentation;

  @ParameterizedTest(name = "{0}")
  @CsvSource({"short documentation, 1", "documentation exceeding the token window, 230"})
  void keepsDocumentationHistoryWithinTheConsultationInvocation(String description, int rows) {
    String memoryId = "consultation:" + UUID.randomUUID();
    String question = "List all CIP codes and exact CIP-4101 method/path pairs from errors#0.";
    String page = "| CIP-4101 | POST | /orders | Parameter missing |\n".repeat(rows);
    when(documentation.readDocumentation(anyString(), anyString(), anyString())).thenAnswer(call -> {
      assertEquals(memoryId, call.getArgument(0));
      return page;
    });
    var script = new ScriptedStreamingChatModel(
        Turn.tool("page-start", request -> {
          var names = request.parameters().toolSpecifications().stream().map(tool -> tool.name()).toList();
          assertTrue(names.containsAll(java.util.List.of("searchDocumentation", "readDocumentation",
              "describeElementPatchSchema", "describeElementProperty")), names.toString());
        }, "readDocumentation", "{\"id\":\"errors#0\",\"cursor\":\"\"}"),
        Turn.tool("page-next", request -> {}, "readDocumentation", "{\"id\":\"errors#0\",\"cursor\":\"next\"}"),
        Turn.tool("page-more", request -> {}, "readDocumentation", "{\"id\":\"errors#0\",\"cursor\":\"more\"}"),
        Turn.tool("page-end", request -> {}, "readDocumentation", "{\"id\":\"errors#0\",\"cursor\":\"end\"}"),
        Turn.text("answer", request -> {
          assertEquals(java.util.List.of(question), request.messages().stream()
              .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).map(UserMessage::singleText).toList());
          assertEquals(4, request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        }, "CIP-4101: POST /orders"),
        Turn.text("independent turn", request -> {
          assertEquals(java.util.List.of("Explain HTTP Trigger."), request.messages().stream()
              .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).map(UserMessage::singleText).toList());
          assertEquals(0, request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        }, "HTTP Trigger receives HTTP requests."));
    doAnswer(call -> {
      script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    agent.chat(memoryId, question)
        .collect().asList().await().atMost(Duration.ofSeconds(20));

    agent.chat(memoryId, "Explain HTTP Trigger.")
        .collect().asList().await().atMost(Duration.ofSeconds(20));

    script.assertComplete();
  }
}
