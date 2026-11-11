package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;

class A2aCommandFingerprintTest {

  @Test
  void semanticallyIdenticalMessagesShareFingerprint() throws Exception {
    Message a =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-a")
            .parts(List.of(new TextPart("  hello  ")))
            .build();
    Message b =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-b")
            .parts(List.of(new TextPart("hello")))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(a);
    assertEquals(
        A2aCommandFingerprint.compute(a, command),
        A2aCommandFingerprint.compute(b, A2aInboundMessageParser.parse(b)));
  }

  @Test
  void correlationIdsDoNotChangeFingerprintWhenClientOmittedThem() throws Exception {
    // RequestContext stamps generated taskId/contextId onto Message before the executor
    // fingerprints. Client-supplied IDs were null at the handler boundary, so both must match.
    Message withoutIds =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("create chain")))
            .build();
    Message withSdkIds =
        Message.builder(withoutIds)
            .taskId("generated-task")
            .contextId("generated-context")
            .build();
    assertEquals(
        A2aCommandFingerprint.compute(
            withoutIds, A2aInboundMessageParser.parse(withoutIds), null, null),
        A2aCommandFingerprint.compute(
            withSdkIds, A2aInboundMessageParser.parse(withSdkIds), null, null));
  }

  @Test
  void differentClientTaskIdsProduceDifferentFingerprints() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("create chain")))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(message);
    assertNotEquals(
        A2aCommandFingerprint.compute(message, command, "task-a", null),
        A2aCommandFingerprint.compute(message, command, "task-b", null));
  }

  @Test
  void differentClientContextIdsProduceDifferentFingerprints() throws Exception {
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("create chain")))
            .build();
    InboundCommand command = A2aInboundMessageParser.parse(message);
    assertNotEquals(
        A2aCommandFingerprint.compute(message, command, null, "ctx-a"),
        A2aCommandFingerprint.compute(message, command, null, "ctx-b"));
  }

  @Test
  void differentTextProducesDifferentFingerprint() throws Exception {
    Message a =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("one")))
            .build();
    Message b =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("two")))
            .build();
    assertNotEquals(
        A2aCommandFingerprint.compute(a, A2aInboundMessageParser.parse(a)),
        A2aCommandFingerprint.compute(b, A2aInboundMessageParser.parse(b)));
  }

  @Test
  void structuredFieldOrderDoesNotChangeFingerprint() throws Exception {
    Message a =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .taskId("task-1")
            .parts(
                List.of(
                    new DataPart(
                        Map.of(
                            "action",
                            "approve",
                            "artifactType",
                            "implementation-plan",
                            "artifactHash",
                            "abc",
                            "revision",
                            1))))
            .build();
    Message b =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .taskId("task-1")
            .parts(
                List.of(
                    new DataPart(
                        new java.util.LinkedHashMap<>(
                            Map.of(
                                "revision",
                                1,
                                "artifactHash",
                                "abc",
                                "artifactType",
                                "implementation-plan",
                                "action",
                                "approve")))))
            .build();
    assertEquals(
        A2aCommandFingerprint.compute(a, A2aInboundMessageParser.parse(a)),
        A2aCommandFingerprint.compute(b, A2aInboundMessageParser.parse(b)));
  }
}
