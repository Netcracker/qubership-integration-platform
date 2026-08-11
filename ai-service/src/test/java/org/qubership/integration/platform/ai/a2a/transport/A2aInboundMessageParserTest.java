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
}
