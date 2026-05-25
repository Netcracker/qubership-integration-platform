package org.qubership.integration.platform.ai.chat.mapper;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.model.UiWithProgressRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiWithProgressToChatRequestMapperTest {

  @Test
  void toInternalMapsChainContextIntoAttachment() {
    UiWithProgressRequest ui = new UiWithProgressRequest();
    UiWithProgressRequest.UiMessage msg = new UiWithProgressRequest.UiMessage();
    msg.setRole("user");
    msg.setContent("Plan this chain");
    ui.setMessages(List.of(msg));
    ui.setContext(
        Map.of(
            "type", "chain",
            "chainId", "chain-42",
            "compactSchema", Map.of("chainId", "chain-42", "elements", List.of())));

    ChatRequest internal = UiWithProgressToChatRequestMapper.toInternal(ui);

    assertNotNull(internal);
    assertTrue(internal.getAttachment().contains("chain-42"));
    assertTrue(internal.getAttachment().contains("compactSchema"));
  }
}
