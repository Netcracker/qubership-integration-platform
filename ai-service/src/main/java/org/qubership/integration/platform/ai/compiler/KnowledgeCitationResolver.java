package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;
import org.qubership.integration.platform.ai.productpipeline.knowledge.CanonicalKnowledgeObject;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeObjectResult;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Resolves LLM-selected reference IDs against the pinned runtime knowledge package. */
@ApplicationScoped
class KnowledgeCitationResolver {

  private final ConversationEvidenceStore evidenceStore;
  private final KnowledgeClient knowledgeClient;
  private final KnowledgeContextProvider contextProvider;

  @Inject
  KnowledgeCitationResolver(
      ConversationEvidenceStore evidenceStore,
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider contextProvider) {
    this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    this.knowledgeClient = Objects.requireNonNull(knowledgeClient, "knowledgeClient");
    this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
  }

  List<QipKnowledgeCitation> resolve(String conversationId, List<String> referenceIds) {
    if (referenceIds == null) {
      return null;
    }
    if (referenceIds.isEmpty()) {
      return List.of();
    }
    EvidenceSnapshot.Knowledge evidence =
        evidenceStore
            .find(conversationId)
            .map(accumulator -> accumulator.toSnapshot(conversationId).knowledge())
            .orElseThrow(() -> new IllegalArgumentException("Runtime knowledge context is missing"));
    return referenceIds.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .distinct()
        .map(referenceId -> resolveOne(conversationId, evidence, referenceId))
        .toList();
  }

  private QipKnowledgeCitation resolveOne(
      String conversationId, EvidenceSnapshot.Knowledge evidence, String referenceId) {
    if (referenceId.isEmpty() || !evidence.objectIds().contains(referenceId)) {
      throw new IllegalArgumentException(
          "Knowledge reference was not provided to this run: " + referenceId);
    }
    KnowledgeObjectResult result =
        knowledgeClient.exact(contextProvider.forConversation(conversationId), referenceId);
    CanonicalKnowledgeObject object = result.object();
    String sourcePath = object.source().document();
    if (object.source().sectionId() != null && !object.source().sectionId().isBlank()) {
      sourcePath += "#" + object.source().sectionId();
    }
    String version = result.identity().packageRef().knowledgeVersion();
    return new QipKnowledgeCitation(
        object.id(),
        QipKnowledgeRefType.KNOWLEDGE_OBJECT,
        sourcePath,
        new QipKnowledgePackVersion(version, version),
        object.summary());
  }
}
