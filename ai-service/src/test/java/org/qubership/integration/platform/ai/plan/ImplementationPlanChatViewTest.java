package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImplementationPlanChatViewTest {

  @Test
  void omitsHashMetadataLinesButKeepsOtherPlanContent() {
    String stored =
        """
        # Implementation plan: HealthProxy

        Schema version: 2
        Binding resolution policy: CATALOG_FIRST_V1
        Design input: normalized-design-flow/flow-1
        Design input hash: e7108e1c6fdda11b7d2af9181ebecdae006f1ff4d83fce8245097b3c0af1b597
        Source report hash: 2339890f5e7b5b39c31e276cb1827f0e865885dfc2992bbe91e78ebc9c63ef0a
        Compiler catalog hash: 98ce0714699603131bd3fd256ea089e67013c37961461431ba7c082abc13e90a

        ## Planner steps
        1. Call health endpoint
        """;

    String chat = ImplementationPlanChatView.forChatReview(stored);

    assertTrue(chat.contains("# Implementation plan: HealthProxy"));
    assertTrue(chat.contains("Schema version: 2"));
    assertTrue(chat.contains("Design input: normalized-design-flow/flow-1"));
    assertTrue(chat.contains("## Planner steps"));
    assertTrue(chat.contains("Call health endpoint"));
    assertFalse(chat.contains("Design input hash:"));
    assertFalse(chat.contains("Source report hash:"));
    assertFalse(chat.contains("Compiler catalog hash:"));
    assertFalse(chat.contains("e7108e1c6fdda11b"));
    assertFalse(chat.contains("2339890f5e7b5b39"));
    assertFalse(chat.contains("98ce071469960313"));
  }

  @Test
  void blankInputStaysBlank() {
    assertEquals("", ImplementationPlanChatView.forChatReview(null));
    assertEquals("   ", ImplementationPlanChatView.forChatReview("   "));
  }
}
