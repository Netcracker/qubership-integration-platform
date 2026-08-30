package org.qubership.integration.platform.ai.chat.decision;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecTitleExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.storage.S3Service;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;

/**
 * Chat/agent-layer gate that asks whether to import uploaded API specifications into the runtime
 * catalog. Produces a grouped decision card and the approval record the pipeline stage consumes.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class UploadedSpecsApprovalHandler {

  public static final String ARTIFACT_TYPE = "uploaded-specs-import-proposal";

  private static final String APPROVE_KIND = "approve";
  private static final String STAGE_ID = "uploaded-specs-approval";
  private static final String CAPABILITY_ID = "uploaded-specs-approval-handler";

  private final ConversationService conversationService;
  private final S3Service s3Service;

  /** True when the conversation has allowed S3 attachment keys to import. */
  public boolean needsApproval(String conversationId) {
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    return keys != null && !keys.isEmpty();
  }

  /** Returns the hash of the current attachment keys, or {@code null} when there are none. */
  public String attachmentHash(String conversationId) {
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    if (keys == null || keys.isEmpty()) {
      return null;
    }
    return hashKeys(keys);
  }

  /** Builds the grouped decision card shown before a CREATE run imports uploaded specs. */
  public ChatEvent.Decision createDecision(String conversationId) {
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    if (keys == null || keys.isEmpty()) {
      throw new IllegalStateException("No uploaded specifications to approve for " + conversationId);
    }

    List<String> displayNames =
        keys.stream().map(this::resolveDisplayNameFromKey).toList();
    String hash = hashKeys(keys);

    return new ChatEvent.Decision(
        decisionId(hash),
        APPROVE_KIND,
        "Import uploaded API specifications into the catalog? " + displayNames,
        ARTIFACT_TYPE,
        hash,
        0L,
        null,
        List.of(),
        List.of("approve", "clarify"));
  }

  /** Converts an approved decision into the record the auto-import stage reads. */
  public ApprovalRecordV2 toApprovalRecord(ChatEvent.Decision decision, List<String> attachmentKeys) {
    Objects.requireNonNull(decision, "decision");
    CompilationArtifacts.Reference target =
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.APPROVAL_RECORD, decision.id(), decision.artifactHash());
    return new ApprovalRecordV2(
        target,
        decision.artifactHash(),
        List.of(),
        "user",
        null,
        Instant.now(),
        null,
        null,
        attachmentKeys == null ? List.of() : List.copyOf(attachmentKeys));
  }

  /** Writes the approval record as an APPROVAL_RECORD artifact for the given run. */
  public CompilationArtifacts.Reference appendApprovalRecord(
      String runId, String conversationId, ChatEvent.Decision decision, ProductPipelineArtifactStore artifactStore) {
    return appendApprovalRecord(runId, conversationId, decision, "user", artifactStore);
  }

  /** Writes the approval record as an APPROVAL_RECORD artifact for the given run and actor. */
  public CompilationArtifacts.Reference appendApprovalRecord(
      String runId,
      String conversationId,
      ChatEvent.Decision decision,
      String actor,
      ProductPipelineArtifactStore artifactStore) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(artifactStore, "artifactStore");

    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    ApprovalRecordV2 record = toApprovalRecord(decision, keys);

    CompilationArtifacts.Revision revision =
        artifactStore.append(
            new CompilationArtifacts.AppendCommand(
                runId,
                CompilationArtifacts.Kind.APPROVAL_RECORD,
                "2",
                CAPABILITY_ID,
                "1",
                record,
                List.of(),
                null,
                new ArtifactProvenance(
                    runId,
                    STAGE_ID,
                    "create-chain",
                    "2",
                    "",
                    CAPABILITY_ID,
                    "1",
                    "")));
    return revision.reference();
  }

  private String resolveDisplayNameFromKey(String key) {
    String filename = filenameFromKey(key);
    try {
      byte[] content = s3Service.readObjectBytes(key);
      return UploadedSpecTitleExtractor.resolveDisplayName(filename, content);
    } catch (RuntimeException e) {
      return filename;
    }
  }

  private String filenameFromKey(String key) {
    int slash = key.lastIndexOf('/');
    return slash >= 0 ? key.substring(slash + 1) : key;
  }

  private String hashKeys(List<String> keys) {
    List<String> sorted = keys.stream().sorted().toList();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < sorted.size(); i++) {
        if (i > 0) {
          digest.update((byte) ',');
        }
        digest.update(sorted.get(i).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String decisionId(String hash) {
    return ARTIFACT_TYPE + ":" + hash;
  }
}
