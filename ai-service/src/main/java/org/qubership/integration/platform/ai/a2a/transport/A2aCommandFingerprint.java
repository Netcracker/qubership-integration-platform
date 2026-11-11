package org.qubership.integration.platform.ai.a2a.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;

/**
 * Versioned canonical command fingerprint for Message idempotency.
 *
 * <p>Includes operation kind, client-supplied {@code taskId}/{@code contextId} (or null when the
 * client omitted them), role, ordered typed parts, and normalized structured action fields.
 * Excludes {@code messageId}, headers, JSON field order noise, transport whitespace, and
 * SDK-generated identifiers that were not present on the inbound Message.
 *
 * <p>Capture client-supplied correlation IDs through {@link A2aClientCorrelationCarrier} at the
 * request handler before the SDK stamps generated values. Pass those captured values into {@link
 * #compute(Message, InboundCommand, String, String)}. When both are null, this class treats Message
 * {@code taskId}/{@code contextId} as absent so unit tests and lost-initial retries stay stable.
 */
public final class A2aCommandFingerprint {

  public static final String VERSION = "v1";

  private static final ObjectMapper CANONICAL =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private A2aCommandFingerprint() {}

  public static String compute(Message message, InboundCommand command) {
    return compute(message, command, null, null);
  }

  public static String compute(
      Message message, InboundCommand command, String clientTaskId, String clientContextId) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(command, "command");

    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("v", VERSION);
    descriptor.put("op", operationKind(command));
    descriptor.put("clientTaskId", blankToNull(clientTaskId));
    descriptor.put("clientContextId", blankToNull(clientContextId));
    descriptor.put("role", message.role() == null ? null : message.role().name());
    descriptor.put("parts", normalizeParts(message.parts()));
    descriptor.put("command", normalizeCommand(command));
    return sha256Hex(toCanonicalJson(descriptor));
  }

  public static String operationKind(InboundCommand command) {
    if (command instanceof InboundCommand.ClarifyText) {
      return "clarify";
    }
    if (command instanceof InboundCommand.Approve) {
      return "approve";
    }
    throw new IllegalArgumentException("Unsupported command: " + command.getClass().getName());
  }

  private static Map<String, Object> normalizeCommand(InboundCommand command) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    if (command instanceof InboundCommand.ClarifyText clarify) {
      normalized.put("text", clarify.text() == null ? "" : clarify.text().strip());
      return normalized;
    }
    if (command instanceof InboundCommand.Approve approve) {
      normalized.put("artifactType", approve.artifactType());
      normalized.put("artifactHash", approve.artifactHash());
      normalized.put("revision", approve.revision());
      normalized.put("comment", approve.comment() == null ? "" : approve.comment().strip());
      return normalized;
    }
    throw new IllegalArgumentException("Unsupported command: " + command.getClass().getName());
  }

  private static List<Map<String, Object>> normalizeParts(List<Part<?>> parts) {
    List<Map<String, Object>> normalized = new ArrayList<>();
    if (parts == null) {
      return normalized;
    }
    for (Part<?> part : parts) {
      Map<String, Object> entry = new LinkedHashMap<>();
      if (part instanceof TextPart textPart) {
        entry.put("kind", "text");
        entry.put("text", textPart.text() == null ? "" : textPart.text().strip());
      } else if (part instanceof DataPart dataPart) {
        entry.put("kind", "data");
        entry.put("data", normalizeValue(dataPart.data()));
      } else {
        entry.put("kind", part.getClass().getSimpleName());
      }
      normalized.add(entry);
    }
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private static Object normalizeValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() instanceof String key) {
          sorted.put(key, normalizeValue(entry.getValue()));
        }
      }
      return sorted;
    }
    if (value instanceof List<?> list) {
      List<Object> items = new ArrayList<>(list.size());
      for (Object item : list) {
        items.add(normalizeValue(item));
      }
      return items;
    }
    if (value instanceof String text) {
      return text.strip();
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String toCanonicalJson(Object value) {
    try {
      return CANONICAL.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize command fingerprint descriptor", e);
    }
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for command fingerprints", e);
    }
  }
}
