package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** One explicit positive or negative requirement fact with a stable source id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementFact(
    String sourceFactId,
    RequirementFactPolarity polarity,
    RequirementFactKind kind,
    String capabilityKey,
    String text,
    @Description("SERVICE_CALL catalog or system display name, e.g. Petstore Ext")
        String participant,
    @Description("Kafka consume operation, HTTP operation id, or SERVICE_CALL operationQuery")
        String operation,
    @Description("Kafka topic when capabilityKey is kafka-trigger-2") String topic,
    @Description("HTTP method when capabilityKey is http-trigger, e.g. GET") String httpMethod,
    @Description("HTTP path when capabilityKey is http-trigger, e.g. /pet/{petId}") String path) {

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
    participant = blankToEmpty(participant);
    operation = blankToEmpty(operation);
    topic = blankToEmpty(topic);
    httpMethod = blankToEmpty(httpMethod);
    path = blankToEmpty(path);
  }

  /** Compatibility constructor used by tests and older capture JSON without identity fields. */
  public RequirementFact(
      String sourceFactId,
      RequirementFactPolarity polarity,
      RequirementFactKind kind,
      String capabilityKey,
      String text) {
    this(sourceFactId, polarity, kind, capabilityKey, text, "", "", "", "", "");
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

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }
}
