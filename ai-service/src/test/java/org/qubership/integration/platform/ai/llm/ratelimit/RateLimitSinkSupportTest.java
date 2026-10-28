package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;

class RateLimitSinkSupportTest {
  @Test
  void outputLimitIsReportedAsAnErrorInsteadOfSuccessfulCompletion() {
    var handler = org.mockito.Mockito.mock(StreamingChatResponseHandler.class);
    var sink = RateLimitSinkSupport.completionAwareHandler(handler, new AtomicBoolean());
    sink.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Partial answer"))
        .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build());
    var failure = org.mockito.ArgumentCaptor.forClass(Throwable.class);
    verify(handler).onError(failure.capture());
    org.junit.jupiter.api.Assertions.assertEquals("LLM_OUTPUT_INCOMPLETE: model output limit reached",
        failure.getValue().getMessage());
    verify(handler, never()).onCompleteResponse(any());
  }

  @Test
  void requiredCompletionPreservesEveryStreamingCallbackAndCompletesBackoffBeforeRefusingText() {
    var handler = mock(StreamingChatResponseHandler.class);
    var sink = RateLimitSinkSupport.completionAwareHandler(handler, new AtomicBoolean(true), true);
    var partial = mock(PartialResponse.class);
    var partialContext = mock(PartialResponseContext.class);
    var thinking = mock(PartialThinking.class);
    var thinkingContext = mock(PartialThinkingContext.class);
    var tool = mock(PartialToolCall.class);
    var toolContext = mock(PartialToolCallContext.class);
    var completeTool = mock(CompleteToolCall.class);
    sink.onPartialResponse("partial");
    sink.onPartialResponse(partial, partialContext);
    sink.onPartialThinking(thinking);
    sink.onPartialThinking(thinking, thinkingContext);
    sink.onPartialToolCall(tool);
    sink.onPartialToolCall(tool, toolContext);
    sink.onCompleteToolCall(completeTool);
    verify(handler).onPartialResponse("partial");
    verify(handler).onPartialResponse(partial, partialContext);
    verify(handler).onPartialThinking(thinking);
    verify(handler).onPartialThinking(thinking, thinkingContext);
    verify(handler).onPartialToolCall(tool);
    verify(handler).onPartialToolCall(tool, toolContext);
    verify(handler).onCompleteToolCall(completeTool);

    var events = new ArrayList<ChatEvent>();
    LlmRateLimitBackoffSink.bind(events::add);
    try {
      LlmRateLimitBackoffSink.onBackoff(1, 1);
      assertEquals(1, events.size());
      sink.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("no tool action")).build());
      assertEquals(2, events.size(), "Protocol refusal retains normal backoff completion.");
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }
    var error = ArgumentCaptor.forClass(Throwable.class);
    verify(handler).onError(error.capture());
    assertInstanceOf(IllegalStateException.class, error.getValue());
    assertTrue(error.getValue().getMessage().contains("without a tool action"));
    verify(handler, never()).onCompleteResponse(any());
  }

  @Test
  void actualToolResponsesAndOrdinaryTextKeepNormalCompletion() {
    var tool = ToolExecutionRequest.builder().id("actual").name("readRequirementDraft").arguments("{}").build();
    var response = ChatResponse.builder().aiMessage(AiMessage.from(tool)).build();
    var required = mock(StreamingChatResponseHandler.class);
    RateLimitSinkSupport.completionAwareHandler(required, new AtomicBoolean(), true).onCompleteResponse(response);
    verify(required).onCompleteResponse(response);
    verify(required, never()).onError(any());

    var text = ChatResponse.builder().aiMessage(AiMessage.from("ordinary text")).build();
    var ordinary = mock(StreamingChatResponseHandler.class);
    RateLimitSinkSupport.completionAwareHandler(ordinary, new AtomicBoolean()).onCompleteResponse(text);
    verify(ordinary).onCompleteResponse(text);
    verify(ordinary, never()).onError(any());
  }
}
