package org.qubership.integration.platform.ai.chat.guardrail;

import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;

import java.util.Optional;

/**
 * Inputs for topic guard: user text, attachment manifest, and conversation context (no file body).
 */
public record GuardrailTurnContext(
    String userMessage,
    AttachmentManifest attachmentManifest,
    boolean hasAttachments,
    String conversationId,
    String recentTranscript,
    Optional<ActiveChainPlanSnapshot> activePlan) {

  public GuardrailTurnContext {
    userMessage = userMessage != null ? userMessage : "";
    attachmentManifest =
        attachmentManifest != null ? attachmentManifest : AttachmentManifest.empty();
    recentTranscript = recentTranscript != null ? recentTranscript : "";
    activePlan = activePlan != null ? activePlan : Optional.empty();
  }

  public String toClassifierMessage() {
    return GuardrailClassificationText.toClassifierMessage(
        userMessage, attachmentManifest, hasAttachments);
  }

  public boolean isEmptyTurn() {
    return userMessage.isBlank() && !hasAttachments;
  }
}
