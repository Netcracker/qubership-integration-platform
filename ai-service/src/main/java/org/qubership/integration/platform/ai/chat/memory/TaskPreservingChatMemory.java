package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Keeps the current rendered generator task when the configured history window evicts it. */
public final class TaskPreservingChatMemory implements ChatMemory {
  private final ChatMemory delegate;
  private UserMessage task;

  public TaskPreservingChatMemory(ChatMemory delegate) {
    this.delegate = delegate;
  }

  @Override
  public Object id() {
    return delegate.id();
  }

  @Override
  public void add(ChatMessage message) {
    if (message instanceof UserMessage user) task = user;
    delegate.add(message);
  }

  @Override
  public List<ChatMessage> messages() {
    var history = delegate.messages();
    if (task == null || history.contains(task)) return history;
    var messages = new ArrayList<>(history);
    int index = 0;
    while (index < messages.size() && messages.get(index) instanceof SystemMessage) index++;
    messages.add(index, task);
    return messages;
  }

  @Override
  public void clear() {
    task = null;
    delegate.clear();
  }

  @Singleton
  @Unremovable
  public static final class Provider implements Supplier<ChatMemoryProvider> {
    private final ChatMemoryProvider configured;

    @Inject
    public Provider(ChatMemoryProvider configured) {
      this.configured = configured;
    }

    @Override
    public ChatMemoryProvider get() {
      return id -> new TaskPreservingChatMemory(configured.get(id));
    }
  }
}
