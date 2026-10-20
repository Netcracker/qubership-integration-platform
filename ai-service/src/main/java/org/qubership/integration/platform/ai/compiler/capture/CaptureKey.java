package org.qubership.integration.platform.ai.compiler.capture;

import java.util.Objects;
import java.util.Optional;

/**
 * Key for a typed capture slot. Conversation-scoped slots omit capability; capability-scoped
 * slots require a non-blank capability id.
 */
public record CaptureKey(String conversationId, CaptureSlot slot, Optional<String> capabilityId) {

  public CaptureKey {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(capabilityId, "capabilityId");
    requireNonBlank(conversationId, "conversationId");
    if (slot.scope() == CaptureSlot.Scope.CONVERSATION) {
      if (capabilityId.isPresent()) {
        throw new IllegalArgumentException(
            "conversation-scoped slot must not include capabilityId: " + slot);
      }
    } else if (slot.scope() == CaptureSlot.Scope.CAPABILITY) {
      if (capabilityId.isEmpty() || capabilityId.get().isBlank()) {
        throw new IllegalArgumentException(
            "capability-scoped slot requires non-blank capabilityId: " + slot);
      }
    } else {
      throw new IllegalArgumentException("unknown slot scope: " + slot.scope());
    }
  }

  public static CaptureKey conversation(CaptureSlot slot, String conversationId) {
    Objects.requireNonNull(slot, "slot");
    if (slot.scope() != CaptureSlot.Scope.CONVERSATION) {
      throw new IllegalArgumentException(
          "conversation factory rejects capability-scoped slot: " + slot);
    }
    return new CaptureKey(conversationId, slot, Optional.empty());
  }

  public static CaptureKey capability(
      CaptureSlot slot, String conversationId, String capabilityId) {
    Objects.requireNonNull(slot, "slot");
    if (slot.scope() != CaptureSlot.Scope.CAPABILITY) {
      throw new IllegalArgumentException(
          "capability factory rejects conversation-scoped slot: " + slot);
    }
    requireNonBlank(capabilityId, "capabilityId");
    return new CaptureKey(conversationId, slot, Optional.of(capabilityId));
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank");
    }
  }
}
