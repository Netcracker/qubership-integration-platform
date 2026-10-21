package org.qubership.integration.platform.ai.plan.mapping.envelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable mapper-2 attribute IDs: SHA-256(kind + newline + jsonPath), truncated to 32 hex chars. */
final class AttributeIds {

  private AttributeIds() {}

  static String forPath(String kind, String jsonPath) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(jsonPath, "jsonPath");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((kind + "\n" + jsonPath).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
