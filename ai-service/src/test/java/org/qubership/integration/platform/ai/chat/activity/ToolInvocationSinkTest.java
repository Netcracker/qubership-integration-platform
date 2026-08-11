package org.qubership.integration.platform.ai.chat.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;

class ToolInvocationSinkTest {

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
  }

  @Test
  void setParentSkillIdNestsSubsequentToolSteps() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add);
    try {
      ToolInvocationSink.setParentSkillId("skill:brainstorming");
      ToolInvocationSink.onInvoke("captureRequirementDraft");
      ToolInvocationSink.onComplete("captureRequirementDraft");
    } finally {
      ToolInvocationSink.unbind();
    }

    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:brainstorming", running.parentId());
  }

  @Test
  void emitsRunningThenCompletedWithoutPayloadFields() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add);
    try {
      ToolInvocationSink.onInvoke("captureSelectedPattern", "skill:cip-auth-generator");
      ToolInvocationSink.onComplete("captureSelectedPattern", "skill:cip-auth-generator");
    } finally {
      ToolInvocationSink.unbind();
    }

    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("tool", running.kind());
    assertEquals("tool:captureSelectedPattern", running.id());
    assertEquals("running", running.status());
    assertEquals("captureSelectedPattern", running.label());
    assertEquals("skill:cip-auth-generator", running.parentId());

    ChatEvent.Step completed = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("tool", completed.kind());
    assertEquals("tool:captureSelectedPattern", completed.id());
    assertEquals("completed", completed.status());
    assertEquals("captureSelectedPattern", completed.label());
    assertEquals("skill:cip-auth-generator", completed.parentId());
  }

  @Test
  void usesParentSkillIdFromBindWhenOnInvokeOmitsParent() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:pattern-selector");
    try {
      ToolInvocationSink.onInvoke("captureSelectedPattern");
      ToolInvocationSink.onComplete("captureSelectedPattern");
    } finally {
      ToolInvocationSink.unbind();
    }

    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:pattern-selector", running.parentId());
  }

  @Test
  void onFailedEmitsErrorStatus() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:test");
    try {
      ToolInvocationSink.onInvoke("getGoldenPattern");
      ToolInvocationSink.onFailed("getGoldenPattern");
    } finally {
      ToolInvocationSink.unbind();
    }

    ChatEvent.Step error = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("error", error.status());
  }

  @Test
  void noOpWhenUnbound() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.onInvoke("captureSelectedPattern");
    ToolInvocationSink.onComplete("captureSelectedPattern");
    assertTrue(out.isEmpty());
  }

  @Test
  void propagatesBindingThroughMutinyContextOnWorkerThread() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:worker-skill");
    Context context = ToolInvocationSink.attachedContext();
    try {
      Uni.createFrom()
          .voidItem()
          .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
          .onItem()
          .invoke(
              () ->
                  ToolInvocationSink.executeInBoundContext(
                      context,
                      () -> {
                        ToolInvocationSink.onInvoke("captureSelectedPattern");
                        ToolInvocationSink.onComplete("captureSelectedPattern");
                      }))
          .awaitUsing(context)
          .indefinitely();
    } finally {
      ToolInvocationSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:worker-skill", running.parentId());
  }

  @Test
  void resolvesBindingFromSubscribePathContextOnWorkerThread() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:worker-skill");
    Context context = ToolInvocationSink.attachedContext();
    try {
      ToolInvocationSink.propagateBinding(
              context,
              Uni.createFrom()
                  .voidItem()
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .onItem()
                  .invoke(
                      () -> {
                        ToolInvocationSink.onInvoke("captureSelectedPattern");
                        ToolInvocationSink.onComplete("captureSelectedPattern");
                      }))
          .awaitUsing(context)
          .indefinitely();
    } finally {
      ToolInvocationSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:worker-skill", running.parentId());
  }
}
