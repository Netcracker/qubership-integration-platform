package org.qubership.integration.platform.ai.chat.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecsApprovalHandlerTest {

  @Test
  void needsApprovalIsTrueWhenAttachmentsExist() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/spec.yaml"));

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);

    assertTrue(handler.needsApproval("conv-1"));
  }

  @Test
  void needsApprovalIsFalseWhenNoAttachments() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1")).thenReturn(List.of());

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);

    assertFalse(handler.needsApproval("conv-1"));
  }

  @Test
  void createsDecisionWithSpecTitles() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uuid/orders-api.yaml", "uuid/notifications-async.yaml"));
    when(s3Service.readObjectBytes(eq("uuid/orders-api.yaml")))
        .thenReturn("{\"info\":{\"title\":\"Orders API\"}}".getBytes());
    when(s3Service.readObjectBytes(eq("uuid/notifications-async.yaml")))
        .thenReturn("{\"info\":{\"title\":\"Notifications Async API\"}}".getBytes());

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);
    ChatEvent.Decision decision = handler.createDecision("conv-1");

    assertEquals("approve", decision.kind());
    assertEquals("uploaded-specs-import-proposal", decision.artifactType());
    assertNotNull(decision.artifactHash());
    assertTrue(decision.question().contains("Orders API"));
    assertTrue(decision.question().contains("Notifications Async API"));
    assertEquals(List.of("approve", "clarify"), decision.actions());
  }

  @Test
  void toApprovalRecordProducesApprovedRecord() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uuid/orders-api.yaml"));
    when(s3Service.readObjectBytes(eq("uuid/orders-api.yaml")))
        .thenReturn("{\"info\":{\"title\":\"Orders API\"}}".getBytes());

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);
    ChatEvent.Decision decision = handler.createDecision("conv-1");
    ApprovalRecordV2 record = handler.toApprovalRecord(decision, List.of("uuid/orders-api.yaml"));

    assertNotNull(record.target());
    assertEquals(decision.id(), record.target().artifactId());
    assertEquals(decision.artifactHash(), record.target().contentHash());
    assertEquals("user", record.actor());
    assertNotNull(record.approvedAt());
  }

  @Test
  void fallsBackToFilenameWhenS3ReadFails() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uuid/orders-api.yaml"));
    when(s3Service.readObjectBytes(eq("uuid/orders-api.yaml")))
        .thenThrow(new RuntimeException("S3 unavailable"));

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);
    ChatEvent.Decision decision = handler.createDecision("conv-1");

    assertTrue(decision.question().contains("orders-api.yaml"));
    assertFalse(decision.question().contains("Orders API"));
  }

  @Test
  void appendApprovalRecordWritesReadableRevision() {
    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uuid/orders-api.yaml"));
    when(s3Service.readObjectBytes(eq("uuid/orders-api.yaml")))
        .thenReturn("{\"info\":{\"title\":\"Orders API\"}}".getBytes());

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);
    ChatEvent.Decision decision = handler.createDecision("conv-1");

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    ProductPipelineArtifactStore artifactStore =
        new ProductPipelineArtifactStore(
            new CompilationArtifacts(
                new InMemoryArtifactBlobStore(), mapper, Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC)));

    CompilationArtifacts.Reference reference =
        handler.appendApprovalRecord("run-1", "conv-1", decision, artifactStore);

    assertEquals(CompilationArtifacts.Kind.APPROVAL_RECORD, reference.kind());
    CompilationArtifacts.Revision revision =
        artifactStore.get("run-1", reference).orElseThrow();
    ApprovalRecordV2 stored = artifactStore.payload(revision, ApprovalRecordV2.class);
    assertEquals(decision.artifactHash(), stored.target().contentHash());
    assertEquals(decision.id(), stored.target().artifactId());
    assertEquals("user", stored.actor());
  }
}
