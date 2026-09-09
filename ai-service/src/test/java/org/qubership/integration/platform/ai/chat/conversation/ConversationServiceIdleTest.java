package org.qubership.integration.platform.ai.chat.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationServiceIdleTest {

  @Test
  void idleTimeoutEvictsTheConversationEntry() {
    FakeTicker ticker = new FakeTicker();
    ConversationService svc = new ConversationService(Duration.ofHours(1), ticker);
    svc.addMessage("c1", ConversationMessage.user("hello"));

    ticker.advance(Duration.ofHours(2));
    svc.cleanUp();

    assertTrue(svc.getMessages("c1").isEmpty());
  }

  @Test
  void accessRefreshesIdleTimeout() {
    FakeTicker ticker = new FakeTicker();
    ConversationService svc = new ConversationService(Duration.ofHours(1), ticker);
    svc.addMessage("c1", ConversationMessage.user("hello"));

    ticker.advance(Duration.ofMinutes(50));
    assertEquals(1, svc.getMessages("c1").size());
    ticker.advance(Duration.ofMinutes(50));
    svc.cleanUp();

    assertEquals(1, svc.getMessages("c1").size());
  }

  static final class FakeTicker implements Ticker {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }
}
