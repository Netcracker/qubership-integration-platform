package org.qubership.integration.platform.ai.chat.memory;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskPreservingChatMemoryTest {
  @Test
  void retainsRenderedTaskAndToolPairsWhenTheHistoryWindowEvictsTheTask() {
    var delegate = MessageWindowChatMemory.withMaxMessages(3);
    var memory = new TaskPreservingChatMemory(delegate);
    var system = SystemMessage.from("Generator system instructions");
    var task = UserMessage.from("Approved task {literal}, generator instructions and repair feedback");
    var call = ToolExecutionRequest.builder().id("native").name("readDocumentation").arguments("{}").build();
    var ai = AiMessage.from(call);
    var result = ToolExecutionResultMessage.from(call, "Native contract");
    memory.add(system);
    memory.add(task);
    memory.add(ai);
    memory.add(result);

    assertAll(
        () -> assertEquals(List.of(system, ai, result), delegate.messages()),
        () -> assertEquals(List.of(system, task, ai, result), memory.messages()),
        () -> assertEquals(memory.messages(), memory.messages()),
        () -> assertEquals(delegate.id(), memory.id()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void preservesParallelToolCallAndResultOrderWhenHistoryEvictsTheTask(boolean tokenWindow) {
    var system = SystemMessage.from("System");
    var task = UserMessage.from("Task and generator instructions");
    var schema = ToolExecutionRequest.builder().id("schema").name("getElementSchema").arguments("{}").build();
    var docs = ToolExecutionRequest.builder().id("docs").name("readDocumentation").arguments("{}").build();
    var batch = AiMessage.from(List.of(schema, docs));
    var schemaResult = ToolExecutionResultMessage.from(schema, "Native schema");
    var docsResult = ToolExecutionResultMessage.from(docs, "Source documentation");
    var estimator = new CipJtokkitTokenCountEstimator("qwen/qwen3.8-flash");
    int retainedTokens = estimator.estimateTokenCountInMessages(List.of(system, batch, schemaResult, docsResult));
    dev.langchain4j.memory.ChatMemory delegate = tokenWindow
        ? TokenWindowChatMemory.builder().maxTokens(retainedTokens, estimator).build()
        : MessageWindowChatMemory.withMaxMessages(4);
    var memory = new TaskPreservingChatMemory(delegate);
    memory.add(system);
    memory.add(task);
    memory.add(batch);
    memory.add(schemaResult);
    memory.add(docsResult);

    assertAll(
        () -> assertEquals(List.of(system, batch, schemaResult, docsResult), delegate.messages()),
        () -> assertEquals(List.of(system, task, batch, schemaResult, docsResult), memory.messages()));
  }

  @Test
  void retainsOversizedTaskWhenTheTokenWindowEvictsItBeforeTheInitialRequest() {
    var delegate = TokenWindowChatMemory.builder()
        .maxTokens(30, new CipJtokkitTokenCountEstimator("qwen/qwen3.8-flash")).build();
    var memory = new TaskPreservingChatMemory(delegate);
    var system = SystemMessage.from("System");
    var task = UserMessage.from("Complete generator instructions and approved inputs. ".repeat(100));
    memory.add(system);
    memory.add(task);

    assertAll(
        () -> assertEquals(List.of(system), delegate.messages()),
        () -> assertEquals(List.of(system, task), memory.messages()));
  }

  @Test
  void returnsConfiguredHistoryWithoutDuplicatingATaskThatStillFits() {
    var delegate = MessageWindowChatMemory.withMaxMessages(4);
    var memory = new TaskPreservingChatMemory(delegate);
    memory.add(UserMessage.from("Task"));
    memory.add(AiMessage.from("Working"));

    assertEquals(delegate.messages(), memory.messages());
  }

  @Test
  void replacesThePinnedTaskWhenARepairTurnArrives() {
    var memory = new TaskPreservingChatMemory(MessageWindowChatMemory.withMaxMessages(1));
    memory.add(UserMessage.from("Original task"));
    memory.add(AiMessage.from("Attempt"));
    var repair = UserMessage.from("Original task plus current rejection and repair instructions");
    memory.add(repair);
    var ai = AiMessage.from("Repair attempt");
    memory.add(ai);

    assertEquals(List.of(repair, ai), memory.messages());
  }

  @Test
  void clearsPinnedTaskAndHistoryTogether() {
    var memory = new TaskPreservingChatMemory(MessageWindowChatMemory.withMaxMessages(1));
    memory.add(UserMessage.from("Task"));
    memory.add(AiMessage.from("Attempt"));
    memory.clear();
    var next = AiMessage.from("New history");
    memory.add(next);

    assertEquals(List.of(next), memory.messages());
  }

  @Test
  void isolatesPinnedTasksBetweenMemories() {
    var first = new TaskPreservingChatMemory(MessageWindowChatMemory.withMaxMessages(1));
    var second = new TaskPreservingChatMemory(MessageWindowChatMemory.withMaxMessages(1));
    var task = UserMessage.from("First conversation");
    var ai = AiMessage.from("Attempt");
    first.add(task);
    first.add(ai);
    second.add(ai);

    assertAll(
        () -> assertEquals(List.of(task, ai), first.messages()),
        () -> assertEquals(List.of(ai), second.messages()));
  }
}
