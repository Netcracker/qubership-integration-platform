package org.qubership.integration.platform.ai.chat.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

/** Per-conversation pipeline timeline and knowledge pins for evidence snapshots. */
public class ConversationEvidenceAccumulator {

  private static final String SCHEMA_VERSION = "1.0";

  private final List<EvidenceSnapshot.TimelineEntry> timeline = new ArrayList<>();
  private KnowledgePackageRef packageRef;
  private final LinkedHashSet<String> objectIds = new LinkedHashSet<>();
  private int contentChars;

  public synchronized void recordPipeline(String bareId, String status) {
    timeline.add(new EvidenceSnapshot.TimelineEntry("pipeline", bareId, status, null));
  }

  public synchronized void recordSkill(String bareId, String status, String bareParentId) {
    timeline.add(new EvidenceSnapshot.TimelineEntry("skill", bareId, status, bareParentId));
  }

  public synchronized void recordKnowledge(
      KnowledgePackageRef packageRef, List<String> objectIds, int contentChars) {
    Objects.requireNonNull(packageRef, "packageRef");
    if (contentChars < 0) {
      throw new IllegalArgumentException("contentChars must not be negative");
    }
    if (this.packageRef == null) {
      this.packageRef = packageRef;
    } else if (!this.packageRef.equals(packageRef)) {
      throw new IllegalStateException(
          "Knowledge package changed inside one conversation: "
              + this.packageRef.packageChecksum()
              + " -> "
              + packageRef.packageChecksum());
    }
    if (objectIds != null) {
      objectIds.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(id -> !id.isEmpty())
          .forEach(this.objectIds::add);
    }
    this.contentChars = Math.toIntExact((long) this.contentChars + contentChars);
  }

  public synchronized EvidenceSnapshot toSnapshot(String conversationId) {
    return new EvidenceSnapshot(
        SCHEMA_VERSION,
        conversationId,
        new EvidenceSnapshot.Knowledge(packageRef, List.copyOf(objectIds), contentChars),
        List.copyOf(timeline));
  }
}
