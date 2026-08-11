package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class CatalogOutboundLoggingFilterTest {

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
  }

  @Test
  void activityLabelUsesMethodAndPathOnly() {
    assertEquals(
        "POST /v1/chains",
        CatalogOutboundLoggingFilter.activityLabel(
            "post", URI.create("https://catalog.example/v1/chains?x=1")));
    assertEquals(
        "GET /v1/elements/abc",
        CatalogOutboundLoggingFilter.activityLabel(
            "GET", URI.create("http://localhost/v1/elements/abc")));
  }

  @Test
  void activityLabelFansIntoToolInvocationSinkWithoutBodies() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:materialization");
    try {
      String label = CatalogOutboundLoggingFilter.activityLabel("POST", URI.create("/v1/chains"));
      ToolInvocationSink.onInvoke(label);
      ToolInvocationSink.onComplete(label);
    } finally {
      ToolInvocationSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("tool", running.kind());
    assertEquals("POST /v1/chains", running.label());
    assertEquals("skill:materialization", running.parentId());
    assertTrue(running.id().startsWith("tool:"));
  }
}
