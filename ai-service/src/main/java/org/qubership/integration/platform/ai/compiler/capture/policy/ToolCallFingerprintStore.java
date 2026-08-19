package org.qubership.integration.platform.ai.compiler.capture.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-conversation soft-credit ledger keyed by fingerprint identity (ADR 0003). */
@ApplicationScoped
public class ToolCallFingerprintStore {

  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<String, Set<String>> softConsumedByConversation =
      new ConcurrentHashMap<>();

  @Inject
  public ToolCallFingerprintStore(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Test helper without CDI. */
  public ToolCallFingerprintStore() {
    this(new ObjectMapper());
  }

  public String fingerprint(String tool, String capability, Object args) {
    return ToolCallFingerprints.fingerprint(objectMapper, tool, capability, args);
  }

  /** Soft-budget key for a rejection, so a reworded payload does not buy a fresh credit. */
  public String failureFingerprint(String tool, String capability, String message) {
    return ToolCallFingerprints.failureFingerprint(tool, capability, message);
  }

  public boolean softCreditUsed(String conversationId, String fingerprint) {
    if (conversationId == null || conversationId.isBlank() || fingerprint == null) {
      return false;
    }
    Set<String> consumed = softConsumedByConversation.get(conversationId);
    return consumed != null && consumed.contains(fingerprint);
  }

  public void consumeSoftCredit(String conversationId, String fingerprint) {
    if (conversationId == null || conversationId.isBlank() || fingerprint == null) {
      return;
    }
    softConsumedByConversation
        .computeIfAbsent(conversationId, ignored -> ConcurrentHashMap.newKeySet())
        .add(fingerprint);
  }

  public int softCreditsUsed(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return 0;
    }
    Set<String> consumed = softConsumedByConversation.get(conversationId);
    return consumed == null ? 0 : consumed.size();
  }

  public Set<String> softFingerprints(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Set.of();
    }
    Set<String> consumed = softConsumedByConversation.get(conversationId);
    return consumed == null ? Set.of() : Collections.unmodifiableSet(consumed);
  }

  public void clear(String conversationId) {
    if (conversationId != null) {
      softConsumedByConversation.remove(conversationId);
    }
  }
}
