package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class CaptureRepairRunnerMemoryTest {

  @Test
  void repairRetryLeavesWellFormedMemoryAfterTerminalValidationFailure() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    String memoryId = "conv:skill";
    ToolExecutionRequest request =
        ToolExecutionRequest.builder()
            .id("call-orphan")
            .name("captureGraphPatch")
            .arguments("{}")
            .build();
    store.updateMessages(memoryId, List.of(UserMessage.from("go"), AiMessage.from(request)));

    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);
    CaptureRepairRunner runner =
        new CaptureRepairRunner(
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)),
            new CaptureAttemptFeedbackStore(),
            1);

    AtomicInteger calls = new AtomicInteger();
    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              if (calls.get() == 1) {
                return Multi.createFrom()
                    .failure(new CaptureValidationException("terminal validation"));
              }
              return Multi.createFrom().empty();
            },
            () -> false,
            () ->
                Optional.of(
                    new CaptureAttemptFeedback(
                        CaptureFailureKind.VALIDATION, "terminal validation")),
            () -> {},
            "captureGraphPatch",
            "initial",
            true,
            null,
            () -> sanitizer.repairDanglingToolCalls(memoryId))
        .collect()
        .asList()
        .await()
        .indefinitely();

    List<ChatMessage> messages = store.getMessages(memoryId);
    assertEquals(3, messages.size());
    ToolExecutionResultMessage result =
        assertInstanceOf(ToolExecutionResultMessage.class, messages.get(2));
    assertEquals("call-orphan", result.id());
    assertEquals(2, calls.get());
  }
}
