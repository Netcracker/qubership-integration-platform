package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.productpipeline.knowledge.CanonicalKnowledgeObject;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeObjectResult;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeResponseIdentity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;

class KnowledgeCitationResolverTest {

  private static final String CONVERSATION_ID = "conversation-1";
  private static final String REFERENCE_ID = "CIP:LEL-000142";

  private ConversationEvidenceStore evidenceStore;
  private KnowledgeClient knowledgeClient;
  private KnowledgeContextProvider contextProvider;
  private KnowledgeCitationResolver resolver;
  private KnowledgePackageRef packageRef;
  private KnowledgeQueryContext queryContext;

  @BeforeEach
  void setUp() {
    evidenceStore = new ConversationEvidenceStore();
    knowledgeClient = mock(KnowledgeClient.class);
    contextProvider = mock(KnowledgeContextProvider.class);
    resolver = new KnowledgeCitationResolver(evidenceStore, knowledgeClient, contextProvider);
    packageRef =
        new KnowledgePackageRef(
            "CIP@1.0.0", "1.0.0", "1.0.0", "sha256:package", "CERTIFIED", "sha256:cert");
    queryContext = new KnowledgeQueryContext(packageRef);
    when(contextProvider.forConversation(CONVERSATION_ID)).thenReturn(queryContext);
  }

  @Test
  void resolvesProvidedReferenceIdIntoStructuredCitation() {
    evidenceStore
        .getOrCreate(CONVERSATION_ID)
        .recordKnowledge(packageRef, List.of(REFERENCE_ID), 100);
    when(knowledgeClient.exact(queryContext, REFERENCE_ID))
        .thenReturn(
            new KnowledgeObjectResult(
                new KnowledgeResponseIdentity(packageRef), knowledgeObject()));

    QipKnowledgeCitation citation =
        resolver.resolve(CONVERSATION_ID, List.of(REFERENCE_ID)).getFirst();

    assertEquals(REFERENCE_ID, citation.refId());
    assertEquals(QipKnowledgeRefType.KNOWLEDGE_OBJECT, citation.refType());
    assertEquals(
        "language/element-relationships.md#pattern-trigger-try-catch-finally-2",
        citation.sourcePath());
    assertEquals("1.0.0", citation.packVersion().normalized());
    assertEquals("Pattern: Trigger to Try-Catch-Finally-2", citation.snippet());
    verify(knowledgeClient).exact(queryContext, REFERENCE_ID);
  }

  @Test
  void rejectsReferenceIdThatWasNotProvidedToTheRun() {
    evidenceStore
        .getOrCreate(CONVERSATION_ID)
        .recordKnowledge(packageRef, List.of("CIP:OTHER-1"), 100);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve(CONVERSATION_ID, List.of(REFERENCE_ID)));

    assertEquals(
        "Knowledge reference was not provided to this run: " + REFERENCE_ID,
        error.getMessage());
  }

  private static CanonicalKnowledgeObject knowledgeObject() {
    return new CanonicalKnowledgeObject(
        "1.0",
        REFERENCE_ID,
        "LanguageElement",
        "Pattern: Trigger to Try-Catch-Finally-2",
        "Pattern: Trigger to Try-Catch-Finally-2",
        Map.of(),
        List.of(),
        new CanonicalKnowledgeObject.Content("markdown", "body", "raw", List.of()),
        "source",
        "active",
        new CanonicalKnowledgeObject.Source(
            "markdown",
            "language/element-relationships.md",
            "pattern-trigger-try-catch-finally-2",
            "sha256:source",
            "source"));
  }
}
