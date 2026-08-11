package org.qubership.integration.platform.ai.compiler.capture;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns atomic once-only capture protocol and typed in-memory storage for the current turn.
 *
 * <p>Does not own durable chain-plan revisions or adapter-specific feedback.
 */
@ApplicationScoped
public class CaptureSession {

  private final ConcurrentHashMap<CaptureKey, Object> values = new ConcurrentHashMap<>();

  public <T> String accept(
      CaptureKey key, T value, String successMessage, String duplicateMessage) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(successMessage, "successMessage");
    Objects.requireNonNull(duplicateMessage, "duplicateMessage");
    requireAssignable(key.slot(), value);
    Object previous = values.putIfAbsent(key, value);
    if (previous != null) {
      throw new CaptureValidationException(duplicateMessage);
    }
    return successMessage;
  }

  public String acceptAll(
      Map<CaptureKey, Object> valuesToAccept, String successMessage, String duplicateMessage) {
    Objects.requireNonNull(valuesToAccept, "valuesToAccept");
    Objects.requireNonNull(successMessage, "successMessage");
    Objects.requireNonNull(duplicateMessage, "duplicateMessage");
    if (valuesToAccept.isEmpty()) {
      throw new IllegalArgumentException("valuesToAccept must not be empty");
    }

    Map<CaptureKey, Object> normalized = new LinkedHashMap<>(valuesToAccept.size());
    for (Map.Entry<CaptureKey, Object> entry : valuesToAccept.entrySet()) {
      CaptureKey key = Objects.requireNonNull(entry.getKey(), "key");
      Object value = Objects.requireNonNull(entry.getValue(), "value");
      requireAssignable(key.slot(), value);
      normalized.put(key, value);
    }

    synchronized (values) {
      for (CaptureKey key : normalized.keySet()) {
        if (values.containsKey(key)) {
          throw new CaptureValidationException(duplicateMessage);
        }
      }
      values.putAll(normalized);
    }
    return successMessage;
  }

  public <T> Optional<T> get(CaptureKey key, Class<T> valueType) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(valueType, "valueType");
    if (!key.slot().valueType().equals(valueType)) {
      throw new IllegalArgumentException(
          "requested type "
              + valueType.getName()
              + " does not match slot value type "
              + key.slot().valueType().getName());
    }
    Object stored = values.get(key);
    if (stored == null) {
      return Optional.empty();
    }
    if (!valueType.isInstance(stored)) {
      throw new IllegalArgumentException(
          "stored value is not an instance of " + valueType.getName());
    }
    return Optional.of(valueType.cast(stored));
  }

  public boolean isPresent(CaptureKey key) {
    Objects.requireNonNull(key, "key");
    return values.containsKey(key);
  }

  public boolean clearIfSame(CaptureKey key, Object expectedValue) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(expectedValue, "expectedValue");
    return values.remove(key, expectedValue);
  }

  public void clear(CaptureKey key) {
    Objects.requireNonNull(key, "key");
    values.remove(key);
  }

  public void clearConversation(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId must be non-blank");
    }
    for (Map.Entry<CaptureKey, Object> entry : values.entrySet()) {
      if (conversationId.equals(entry.getKey().conversationId())) {
        values.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  private static void requireAssignable(CaptureSlot slot, Object value) {
    if (!slot.valueType().isInstance(value)) {
      throw new IllegalArgumentException(
          "value type "
              + value.getClass().getName()
              + " does not match slot "
              + slot
              + " expected "
              + slot.valueType().getName());
    }
  }
}
