package org.qubership.integration.platform.ai.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** One explicit positive or negative requirement fact with a stable source id. */
public record RequirementFact(
    String sourceFactId,
    RequirementFactPolarity polarity,
    RequirementFactKind kind,
    String capabilityKey,
    String text) {

  public RequirementFact {
    Objects.requireNonNull(polarity, "polarity");
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text is required");
    }
    text = text.trim();
    // LLM tool calls often omit kind; default from polarity so capture still succeeds.
    if (kind == null) {
      kind =
          polarity == RequirementFactPolarity.NEGATIVE
              ? RequirementFactKind.CONSTRAINT
              : RequirementFactKind.BEHAVIOR;
    }
    capabilityKey =
        capabilityKey == null || capabilityKey.isBlank()
            ? ""
            : capabilityKey.trim().toLowerCase(Locale.ROOT);
    if (sourceFactId == null || sourceFactId.isBlank()) {
      sourceFactId = deriveSourceFactId(polarity, text);
    } else {
      sourceFactId = sourceFactId.trim();
    }
  }

  public static RequirementFact of(
      RequirementFactPolarity polarity,
      RequirementFactKind kind,
      String capabilityKey,
      String text) {
    return new RequirementFact(null, polarity, kind, capabilityKey, text);
  }

  public static String deriveSourceFactId(RequirementFactPolarity polarity, String text) {
    Objects.requireNonNull(polarity, "polarity");
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text is required");
    }
    String payload = polarity.name() + '\n' + text.trim();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
