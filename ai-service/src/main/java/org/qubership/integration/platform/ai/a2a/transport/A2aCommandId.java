package org.qubership.integration.platform.ai.a2a.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives a stable, server-owned command identity from the durable caller receipt key. The ID does
 * not depend on mutable Task state.
 */
public final class A2aCommandId {

  private A2aCommandId() {}

  public static String derive(String tenantId, String subjectId, String messageId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("a2a-cmd\0".getBytes(StandardCharsets.UTF_8));
      digest.update(tenantId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(subjectId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(messageId.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Synthetic identity for automatic implementation nested under an approve receipt. */
  public static String autoImplement(String commandId) {
    Objects.requireNonNull(commandId, "commandId");
    return commandId + ":auto-implement";
  }
}
