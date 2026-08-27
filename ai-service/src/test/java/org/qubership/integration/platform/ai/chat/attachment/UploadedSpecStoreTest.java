package org.qubership.integration.platform.ai.chat.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UploadedSpecStoreTest {

  private final UploadedSpecStore store = new UploadedSpecStore();

  @Test
  void registersAndRetrievesEntries() {
    UploadedSpecEntry entry =
        new UploadedSpecEntry("key-1", "order.json", SpecType.OPENAPI, "Order API", "1.0", "GET /orders");
    store.register("conv-1", entry);

    assertEquals(1, store.getAll("conv-1").size());
    assertEquals(entry, store.findByKey("conv-1", "key-1"));
    assertNull(store.findByKey("conv-1", "missing"));
    assertEquals(0, store.getAll("conv-2").size());
  }
}
