package org.qubership.integration.platform.ai.compiler.addon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One addon document loaded from the compiler skill addon pack. */
public record CompilerSkillAddonDocument(String relativePath, String content, String sha256) {

  public CompilerSkillAddonDocument(String relativePath, String content) {
    this(relativePath, content, sha256(content));
  }

  public static String sha256(String content) {
    String payload = content == null ? "" : content;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
