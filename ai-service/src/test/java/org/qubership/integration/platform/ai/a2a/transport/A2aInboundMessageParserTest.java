package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.ContentTypeNotSupportedError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;

class A2aInboundMessageParserTest {

  @Test
  void parsesPlainTextAsClarify() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m1")
            .parts(List.of(new TextPart("Need a chain")))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(message);
    assertInstanceOf(InboundCommand.ClarifyText.class, command);
    assertEquals("Need a chain", ((InboundCommand.ClarifyText) command).text());
  }

  @Test
  void parsesApproveStructuredData() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m2")
            .parts(
                List.of(
                    new DataPart(
                        Map.of(
                            "action", "approve",
                            "artifactType", "integration-design",
                            "artifactHash", "abc",
                            "revision", 3))))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(message);
    InboundCommand.Approve approve = assertInstanceOf(InboundCommand.Approve.class, command);
    assertEquals("integration-design", approve.artifactType());
    assertEquals("abc", approve.artifactHash());
    assertEquals(3L, approve.revision());
    assertEquals("", approve.comment());
  }

  @Test
  void parsesApproveOptionalComment() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m2b")
            .parts(
                List.of(
                    new DataPart(
                        Map.of(
                            "action", "approve",
                            "artifactType", "integration-design",
                            "artifactHash", "abc",
                            "revision", 3,
                            "comment", "looks good"))))
            .build();
    InboundCommand.Approve approve =
        assertInstanceOf(InboundCommand.Approve.class, A2aInboundMessageParser.parse(message));
    assertEquals("looks good", approve.comment());
  }

  @Test
  void parsesPassThroughPlainText() throws Exception {
    InboundCommand.ClarifyText clarify =
        assertInstanceOf(
            InboundCommand.ClarifyText.class,
            A2aInboundMessageParser.parse(textMessage("PASS_THROUGH")));
    assertEquals("pass_through", clarify.text());
  }

  @Test
  void parsesPassThroughStructuredAction() throws Exception {
    InboundCommand.ClarifyText clarify =
        assertInstanceOf(
            InboundCommand.ClarifyText.class,
            A2aInboundMessageParser.parse(structured("pass_through")));
    assertEquals("pass_through", clarify.text());
  }

  @Test
  void parsesDescribeMappingsStructuredAction() throws Exception {
    InboundCommand.ClarifyText clarify =
        assertInstanceOf(
            InboundCommand.ClarifyText.class,
            A2aInboundMessageParser.parse(structured("describe_mappings")));
    assertEquals("describe_mappings", clarify.text());
  }

  @Test
  void parsesRetryStructuredDataAsClarifyText() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m-retry")
            .parts(List.of(new DataPart(Map.of("action", "retry"))))
            .build();
    InboundCommand.ClarifyText clarify =
        assertInstanceOf(InboundCommand.ClarifyText.class, A2aInboundMessageParser.parse(message));
    assertEquals("retry", clarify.text());
  }

  @Test
  void parsesReviseStructuredDataAsClarifyText() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m-revise")
            .parts(List.of(new DataPart(Map.of("action", "revise"))))
            .build();
    InboundCommand.ClarifyText clarify =
        assertInstanceOf(InboundCommand.ClarifyText.class, A2aInboundMessageParser.parse(message));
    assertEquals("revise", clarify.text());
  }

  @Test
  void rejectsPublicImplementAction() {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m3")
            .parts(List.of(new DataPart(Map.of("action", "implement"))))
            .build();
    assertThrows(UnsupportedOperationError.class, () -> A2aInboundMessageParser.parse(message));
  }

  @Test
  void rejectsFileParts() {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m4")
            .parts(List.of(new FilePart(new FileWithBytes("text/plain", "a.txt", "YQ=="))))
            .build();
    assertThrows(
        ContentTypeNotSupportedError.class, () -> A2aInboundMessageParser.parse(message));
  }

  @Test
  void rejectsMalformedApprove() {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m5")
            .parts(List.of(new DataPart(Map.of("action", "approve"))))
            .build();
    InvalidParamsError error =
        assertThrows(InvalidParamsError.class, () -> A2aInboundMessageParser.parse(message));
    assertTrue(error.getMessage().contains("artifactType"));
  }

  @Test
  void explicitClarificationOfferIsPersistedWithTheCommand() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("m-offer")
            .parts(List.of(
                new TextPart("Use HTTPS"),
                new DataPart(Map.of("action", "clarify", "clarificationOfferId", "offer-42"))))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(message);
    assertEquals("Use HTTPS", assertInstanceOf(InboundCommand.ClarifyText.class, command).text());
    String payload = A2aInboundMessageParser.commandPayload(command);
    assertTrue(payload != null && payload.contains("offer-42"), payload);
  }

  @Test
  void redispatchRestoresThePinnedClarificationOffer() {
    var receipt = new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt(
        "task",
        "fingerprint",
        org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState.CLAIMED,
        null,
        null,
        A2aInboundMessageParser.KIND_CONTINUE_CLARIFY,
        4L,
        "{\"text\":\"Use HTTPS\",\"clarificationOfferId\":\"offer-pinned\"}");
    InboundCommand restored = A2aInboundMessageParser.resolveSavedCommand(
        new InboundCommand.ClarifyText("Use HTTPS"), receipt);
    String payload = A2aInboundMessageParser.commandPayload(restored);
    assertTrue(payload != null && payload.contains("offer-pinned"), payload);
  }

  @Test
  void corruptSavedApprovalFailsClosed() {
    var receipt = new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt(
        "task", "fingerprint", org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState.CLAIMED,
        null, null, A2aInboundMessageParser.KIND_APPROVE, 3L, "{broken");
    assertThrows(IllegalStateException.class, () -> A2aInboundMessageParser.resolveSavedCommand(
        new InboundCommand.ClarifyText("approve token"), receipt));
  }

  private static Message textMessage(String text) {
    return Message.builder()
        .role(Message.Role.ROLE_USER)
        .messageId("m-text")
        .parts(List.of(new TextPart(text)))
        .build();
  }

  private static Message structured(String action) {
    return Message.builder()
        .role(Message.Role.ROLE_USER)
        .messageId("m-structured")
        .parts(List.of(new DataPart(Map.of("action", action))))
        .build();
  }
}
