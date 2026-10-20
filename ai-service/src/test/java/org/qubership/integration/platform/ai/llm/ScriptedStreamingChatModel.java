package org.qubership.integration.platform.ai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Test-only provider script. Every response requires an assertion over the actual incoming request. */
public final class ScriptedStreamingChatModel implements StreamingChatModel {
  public record Turn(String name, Consumer<ChatRequest> expect, Consumer<StreamingChatResponseHandler> emit) {
    public static Turn tool(String name, Consumer<ChatRequest> expect, String tool, String arguments) {
      return new Turn(name, request -> {
        assertTrue(request.parameters().toolSpecifications().stream().anyMatch(spec -> tool.equals(spec.name())),
            "Tool is not advertised: " + tool);
        expect.accept(request);
      }, handler -> handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
          ToolExecutionRequest.builder().id(name).name(tool).arguments(arguments).build())).build()));
    }

    public static Turn text(String name, Consumer<ChatRequest> expect, String text) {
      return new Turn(name, expect,
          handler -> handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build()));
    }

    public static Turn failure(String name, Consumer<ChatRequest> expect, RuntimeException failure) {
      return new Turn(name, expect, handler -> handler.onError(failure));
    }
  }

  private final List<Turn> turns;
  private final List<ChatRequest> requests = new ArrayList<>();
  private volatile Throwable mismatch;

  public ScriptedStreamingChatModel(Turn... turns) {
    this.turns = List.of(turns);
  }

  @Override
  public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
    Turn turn;
    synchronized (this) {
      requests.add(request);
      if (requests.size() > turns.size()) {
        mismatch = new AssertionError("Unexpected model request " + requests.size());
        throw (AssertionError) mismatch;
      }
      turn = turns.get(requests.size() - 1);
    }
    try {
      turn.expect().accept(request);
    } catch (AssertionError | RuntimeException failure) {
      mismatch = new AssertionError("Request did not match script turn: " + turn.name(), failure);
      throw (AssertionError) mismatch;
    }
    turn.emit().accept(handler);
  }

  public synchronized List<ChatRequest> requests() {
    return List.copyOf(requests);
  }

  public synchronized void assertComplete() {
    assertNull(mismatch, () -> "Script request mismatch: " + mismatch);
    assertEquals(turns.size(), requests.size(), "Not all provider turns were exercised");
  }
}
