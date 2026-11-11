package org.qubership.integration.platform.ai.chat.activity;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;

class CatalogChangeTrackerTest {
  @Test
  void mergesOnlyThisOperationsFactsAndDrainsOnce() {
    var first = new CatalogChangeTracker();
    var second = new CatalogChangeTracker();
    first.chainChanged("a");
    first.chainChanged("a");
    first.chainChanged("b");
    assertEquals(new ChatEvent.CatalogChanged(List.of("a", "b"), true), first.drain());
    assertNull(first.drain());
    assertNull(second.drain());
    first.foldersChanged(false);
    assertEquals(new ChatEvent.CatalogChanged(List.of(), true), first.drain());
    first.chainChanged("a");
    first.foldersChanged(true);
    assertEquals(new ChatEvent.CatalogChanged(null, true), first.drain());
  }
}
