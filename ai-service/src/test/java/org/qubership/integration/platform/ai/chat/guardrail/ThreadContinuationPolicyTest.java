package org.qubership.integration.platform.ai.chat.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ThreadContinuationPolicyTest {

  private static final String LOG_TRANSCRIPT =
      """
User: Create a chain using attached design. As a service mentioned in the design use "Pet store" in the catalog.

Assistant: Now that we have identified the "Pet store" service and its operations, I will proceed to create the ChainImplementationPlan.

Please provide the details of the design you would like to implement in the chain. If there are specific operations from the "Pet store" service that you want to include, please specify those as well.
""";

  @Test
  void attachmentBoilerplateAfterAssistantDesignRequestAllowed() {
    GuardrailTurnContext turn =
        new GuardrailTurnContext(
            "See attached files.",
            AttachmentManifest.fromObjectKeys(
                List.of("bf1ecfb5/60327ccd-e5f8-4032-a43b-b897568c2b64.md")),
            true,
            "conv-1",
            LOG_TRANSCRIPT,
            Optional.empty());

    assertTrue(ThreadContinuationPolicy.allowAttachmentReply(turn));
  }

  @Test
  void attachmentOnlyNewChatNotAllowed() {
    GuardrailTurnContext turn =
        new GuardrailTurnContext(
            "See attached files.",
            AttachmentManifest.fromObjectKeys(List.of("key/file.md")),
            true,
            "conv-2",
            "(no prior messages in this conversation)",
            Optional.empty());

    assertFalse(ThreadContinuationPolicy.allowAttachmentReply(turn));
  }

  @Test
  void attachmentWithSubstantiveNewTopicNotAllowed() {
    GuardrailTurnContext turn =
        new GuardrailTurnContext(
            "What is the weather today?",
            AttachmentManifest.fromObjectKeys(List.of("key/file.md")),
            true,
            "conv-3",
            LOG_TRANSCRIPT,
            Optional.empty());

    assertFalse(ThreadContinuationPolicy.allowAttachmentReply(turn));
  }
}
