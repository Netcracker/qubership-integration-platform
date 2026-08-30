package org.qubership.integration.platform.ai.chat;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairDraftStore;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;

/**
 * Single entry point for Edit/Regenerate/Clear turn resets: truncate or clear transcript messages
 * and reset conversation-scoped artifact, pipeline, and chat-memory state.
 *
 * <p>Compilation-backed stores read the active compilation from {@link CompilationSessions}. Reset
 * calls {@link CompilationSessions#startNew(String)} exactly once per reset. Do not also call
 * {@link RequirementDraftStore#remove(String)} because it would start a second compilation.
 */
@ApplicationScoped
public class ConversationTurnReset {

  private final ConversationService conversationService;
  private final ChatMemoryStore chatMemoryStore;
  private final InMemorySkillWorkspaceStore workspaceStore;
  private final CaptureSession captureSession;
  private final ChainPlanStore chainPlanStore;
  private final ChainPlanRepairDraftStore chainPlanRepairDraftStore;
  private final CaptureAttemptFeedbackStore captureAttemptFeedbackStore;
  private final CompilationSessions compilationSessions;
  private final RequirementDraftStore requirementDraftStore;
  private final ConversationCatalogCache conversationCatalogCache;
  private final ConversationEvidenceStore conversationEvidenceStore;
  private final PinnedFailureStore pinnedFailureStore;

  @Inject
  ConversationTurnReset(
      ConversationService conversationService,
      ChatMemoryStore chatMemoryStore,
      InMemorySkillWorkspaceStore workspaceStore,
      CaptureSession captureSession,
      ChainPlanStore chainPlanStore,
      ChainPlanRepairDraftStore chainPlanRepairDraftStore,
      CaptureAttemptFeedbackStore captureAttemptFeedbackStore,
      CompilationSessions compilationSessions,
      RequirementDraftStore requirementDraftStore,
      ConversationCatalogCache conversationCatalogCache,
      ConversationEvidenceStore conversationEvidenceStore,
      PinnedFailureStore pinnedFailureStore) {
    this.conversationService = conversationService;
    this.chatMemoryStore = chatMemoryStore;
    this.workspaceStore = workspaceStore;
    this.captureSession = captureSession;
    this.chainPlanStore = chainPlanStore;
    this.chainPlanRepairDraftStore = chainPlanRepairDraftStore;
    this.captureAttemptFeedbackStore = captureAttemptFeedbackStore;
    this.compilationSessions = compilationSessions;
    this.requirementDraftStore = requirementDraftStore;
    this.conversationCatalogCache = conversationCatalogCache;
    this.conversationEvidenceStore = conversationEvidenceStore;
    this.pinnedFailureStore = pinnedFailureStore;
  }

  public void truncateAndReset(String conversationId, int afterMessageIndex) {
    conversationService.truncateAfter(conversationId, afterMessageIndex);
    if (afterMessageIndex < 0) {
      pinnedFailureStore.clearConversation(conversationId);
    } else {
      List<String> remainingContents =
          conversationService.getMessages(conversationId).stream()
              .map(ConversationMessage::content)
              .toList();
      pinnedFailureStore.dropPinsMissingFrom(conversationId, remainingContents);
    }
    resetArtifactInventory(conversationId);
  }

  public void fullReset(String conversationId) {
    conversationService.clearMessages(conversationId);
    pinnedFailureStore.clearConversation(conversationId);
    resetArtifactInventory(conversationId);
  }

  private void resetArtifactInventory(String conversationId) {
    Set<String> completedSkillIds = workspaceStore.completedSkillIds(conversationId);

    clearChatMemory(conversationId, completedSkillIds);

    // Conversation-scoped session keys only — do not clearConversation (would wipe patches).
    captureSession.clear(CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId));
    captureSession.clear(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId));
    captureSession.clear(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, conversationId));
    captureSession.clear(CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId));
    chainPlanStore.remove(conversationId);
    chainPlanRepairDraftStore.remove(conversationId);
    captureAttemptFeedbackStore.clearAll(conversationId);
    conversationCatalogCache.clearConversation(conversationId);
    conversationEvidenceStore.clear(conversationId);

    compilationSessions.startNew(conversationId);
    requirementDraftStore.clearTurnFlags(conversationId);

    workspaceStore.clear(conversationId);
  }

  private void clearChatMemory(String conversationId, Set<String> completedSkillIds) {
    chatMemoryStore.deleteMessages(conversationId);
    for (String capabilityId : completedSkillIds) {
      chatMemoryStore.deleteMessages(
          CompilerSkillMemoryIds.forSkill(conversationId, capabilityId));
    }
  }
}
