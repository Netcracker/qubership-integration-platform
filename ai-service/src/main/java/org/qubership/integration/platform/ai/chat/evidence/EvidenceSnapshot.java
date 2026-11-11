package org.qubership.integration.platform.ai.chat.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

/** Feature-flagged evidence snapshot response DTO (bare ids in timeline). */
public record EvidenceSnapshot(
    String schemaVersion,
    String conversationId,
    Knowledge knowledge,
    List<TimelineEntry> timeline) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Knowledge(
      KnowledgePackageRef packageRef, List<String> objectIds, int contentChars) {
    public Knowledge {
      objectIds = objectIds == null ? List.of() : List.copyOf(objectIds);
      if (contentChars < 0) {
        throw new IllegalArgumentException("contentChars must not be negative");
      }
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TimelineEntry(String kind, String id, String status, String parentId) {}
}
