package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** In-memory document plus the artifact revision. The revision is not stored inside the payload. */
public record WorkDocumentState(String revision, ChainWorkDocument document) {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static WorkDocumentState create(String documentId) {
    return create(documentId, List.of());
  }

  public static WorkDocumentState create(String documentId, List<WorkSource> sources) {
    ChainWorkDocument document =
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            documentId,
            sources,
            List.of(),
            LogicalFlow.empty(),
            WorkProgress.empty());
    return new WorkDocumentState(revisionOf(document), document);
  }

  static WorkDocumentState of(ChainWorkDocument document) {
    return new WorkDocumentState(revisionOf(document), document);
  }

  static String revisionOf(ChainWorkDocument document) {
    try {
      byte[] payload = JSON.writeValueAsBytes(document);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
      return HexFormat.of().formatHex(hash);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot hash the work document.", failure);
    }
  }
}
