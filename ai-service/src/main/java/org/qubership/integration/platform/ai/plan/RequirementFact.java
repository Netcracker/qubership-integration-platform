package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One explicit positive or negative requirement fact with a stable source id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementFact(
    String sourceFactId,
    RequirementFactPolarity polarity,
    RequirementFactKind kind,
    @Description(
            "Native chain-element key. Inbound: http-trigger, chain-trigger-2, async-api-trigger,"
                + " jms-trigger, kafka-trigger-2, mcp-trigger, pubsub-trigger, quartz-scheduler,"
                + " rabbitmq-trigger-2, sds-trigger, or sftp-trigger-2. Outbound direct:"
                + " graphql-sender, http-sender, jms-sender, kafka-sender-2, mail-sender,"
                + " pubsub-sender, rabbitmq-sender-2, or scs-sender. File transfer: sftp-download"
                + " or sftp-upload. Composition: chain-call-2,"
                + " reuse, or reuse-reference.")
        String capabilityKey,
    String text,
    @Description(
            "SERVICE_CALL catalog or system display name, e.g. Petstore Ext. MCP service name when"
                + " capabilityKey is mcp-trigger.")
        String participant,
    @Description(
            "Kafka consume operation, HTTP operation id, or SERVICE_CALL operationQuery."
                + " User-supplied MCP server identifier when capabilityKey is mcp-trigger (copy"
                + " verbatim; leave blank when the user did not name one).")
        String operation,
    @Description("Kafka topic when capabilityKey is kafka-trigger-2") String topic,
    @Description("HTTP method when capabilityKey is http-trigger or http-sender, e.g. GET")
        String httpMethod,
    @Description(
            "HTTP path or URL when capabilityKey is http-trigger or http-sender. Catalog"
                + " chain-trigger-2 UUID when capabilityKey is chain-call-2. Catalog MCP system"
                + " UUID when capabilityKey is mcp-trigger.")
        String path,
    @Description(
            "Stable SERVICE_CALL occurrence id, or catalog Kafka consume id when capabilityKey is async-api-trigger")
        String serviceCallId,
        List<String> interactionIds) {

  public RequirementFact {
    // LLM tool JSON omits fields; throwing here becomes ToolArgumentsException before the tool runs.
    if (polarity == null) {
      polarity = RequirementFactPolarity.POSITIVE;
    }
    text = blankToEmpty(text);
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
    participant = blankToEmpty(participant);
    operation = blankToEmpty(operation);
    topic = blankToEmpty(topic);
    httpMethod = blankToEmpty(httpMethod);
    path = blankToEmpty(path);
    if (kind == RequirementFactKind.SERVICE_CALL) {
      serviceCallId = blankToEmpty(serviceCallId);
      if (sourceFactId == null || sourceFactId.isBlank()) {
        sourceFactId =
            serviceCallId.isEmpty()
                ? deriveSourceFactId(
                    polarity,
                    identitySeed(kind, text, participant, operation, topic, httpMethod, path))
                : serviceCallId;
      } else {
        sourceFactId = sourceFactId.trim();
      }
    } else {
      if (sourceFactId == null || sourceFactId.isBlank()) {
        sourceFactId =
            deriveSourceFactId(
                polarity,
                identitySeed(kind, text, participant, operation, topic, httpMethod, path));
      } else {
        sourceFactId = sourceFactId.trim();
      }
      if (kind == RequirementFactKind.ENDPOINT && "async-api-trigger".equals(capabilityKey)) {
        serviceCallId = blankToEmpty(serviceCallId);
      } else {
        serviceCallId = "";
      }
    }
    interactionIds = interactionIds == null ? List.of() : List.copyOf(interactionIds);
  }

  public RequirementFact(
      String sourceFactId,
      RequirementFactPolarity polarity,
      RequirementFactKind kind,
      String capabilityKey,
      String text,
      String participant,
      String operation,
      String topic,
      String httpMethod,
      String path,
      String serviceCallId) {
    this(sourceFactId, polarity, kind, capabilityKey, text, participant, operation, topic,
        httpMethod, path, serviceCallId, List.of());
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

  /** Compatibility constructor used before service-call occurrence identity existed. */
  public RequirementFact(
      String sourceFactId,
      RequirementFactPolarity polarity,
      RequirementFactKind kind,
      String capabilityKey,
      String text,
      String participant,
      String operation,
      String topic,
      String httpMethod,
      String path) {
    this(
        sourceFactId,
        polarity,
        kind,
        capabilityKey,
        text,
        participant,
        operation,
        topic,
        httpMethod,
        path,
        "");
  }

  public static RequirementFact of(
      RequirementFactPolarity polarity,
      RequirementFactKind kind,
      String capabilityKey,
      String text) {
    return new RequirementFact(null, polarity, kind, capabilityKey, text);
  }

  public boolean needsCatalogBinding() {
    if (polarity != RequirementFactPolarity.POSITIVE) {
      return false;
    }
    if (kind == RequirementFactKind.SERVICE_CALL) {
      return true;
    }
    return kind == RequirementFactKind.ENDPOINT
        && "async-api-trigger".equals(capabilityKey)
        && !serviceCallId.isBlank();
  }

  public RequirementFact withKind(RequirementFactKind newKind) {
    return new RequirementFact(
        sourceFactId,
        polarity,
        newKind,
        capabilityKey,
        text,
        participant,
        operation,
        topic,
        httpMethod,
        path,
        serviceCallId,
        interactionIds);
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

  private static String identitySeed(
      RequirementFactKind kind,
      String text,
      String participant,
      String operation,
      String topic,
      String httpMethod,
      String path) {
    if (!text.isEmpty()) {
      return text;
    }
    String seed =
        String.join("\n", kind.name(), participant, operation, topic, httpMethod, path);
    return seed.isBlank() ? kind.name() : seed;
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }
}
