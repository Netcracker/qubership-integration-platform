package org.qubership.integration.platform.ai.chat.evidence;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.Consumer;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

/** Records pipeline/skill evidence into the accumulator and emits matching SSE steps. */
@ApplicationScoped
public class EvidenceEmitter {

  private static final Logger LOG = Logger.getLogger(EvidenceEmitter.class);

  private final ConversationEvidenceStore store;

  @Inject
  public EvidenceEmitter(ConversationEvidenceStore store) {
    this.store = store;
  }

  /** Records pipeline evidence and returns the matching SSE step (null when recording only). */
  public ChatEvent pipelineStep(String conversationId, String bareStageId, String status) {
    ChatEvent[] captured = new ChatEvent[1];
    pipeline(conversationId, bareStageId, status, event -> captured[0] = event);
    return captured[0];
  }

  /** Prepends a step event before the remainder of a scenario stream. */
  public Multi<ChatEvent> prependStep(ChatEvent step, Multi<ChatEvent> tail) {
    if (step == null) {
      return tail;
    }
    return Multi.createBy().concatenating().streams(Multi.createFrom().item(step), tail);
  }

  public void pipeline(
      String conversationId, String bareStageId, String status, Consumer<ChatEvent> emit) {
    try {
      if (conversationId != null) {
        store.getOrCreate(conversationId).recordPipeline(bareStageId, status);
      }
      if (emit != null) {
        emit.accept(
            ChatEvent.step(
                EvidenceIds.wirePipeline(bareStageId), "pipeline", status, bareStageId, null));
      }
    } catch (Exception e) {
      LOG.warnf(e, "Evidence pipeline emit failed stage=%s status=%s", bareStageId, status);
    }
  }

  public void knowledge(
      String conversationId,
      KnowledgePackageRef packageRef,
      List<String> objectIds,
      int contentChars) {
    try {
      if (conversationId == null) {
        return;
      }
      store
          .getOrCreate(conversationId)
          .recordKnowledge(packageRef, objectIds, contentChars);
    } catch (Exception error) {
      LOG.warnf(
          error,
          "Evidence knowledge emit failed checksum=%s",
          packageRef == null ? null : packageRef.packageChecksum());
    }
  }

  public void skill(
      String conversationId,
      String bareSkillId,
      String status,
      String bareParentId,
      Consumer<ChatEvent> emit) {
    try {
      if (conversationId != null) {
        store.getOrCreate(conversationId).recordSkill(bareSkillId, status, bareParentId);
      }
      if (emit != null) {
        String parentWire =
            bareParentId != null ? EvidenceIds.wirePipeline(bareParentId) : null;
        emit.accept(
            ChatEvent.step(
                EvidenceIds.wireSkill(bareSkillId),
                "skill",
                status,
                bareSkillId,
                parentWire));
      }
    } catch (Exception e) {
      LOG.warnf(e, "Evidence skill emit failed skill=%s status=%s", bareSkillId, status);
    }
  }
}
