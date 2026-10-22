package org.qubership.integration.platform.ai.chain.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;

class ChainContextExtractorTest {

  private static final String CONVERSATION_ID = "conv-chain-context";

  @Test
  void parsesChainIdFromCompactSchemaAttachment() {
    ChainContextExtractor extractor = newExtractor();

    ChatRequest request = new ChatRequest();
    request.setAttachment(
        """
        ## Current Chain: Greetings (ID: chain-42)
        ```json
        {
          "chainId": "chain-42",
          "chainName": "Greetings",
          "elements": [],
          "connections": []
        }
        ```
        """);

    assertEquals("chain-42", extractor.resolveChainId(request, CONVERSATION_ID).orElseThrow());
    assertTrue(extractor.hasChainContext(request, CONVERSATION_ID));
  }

  @Test
  void parsesChainIdFromHeadingWhenJsonMissing() {
    ChainContextExtractor extractor = newExtractor();

    ChatRequest request = new ChatRequest();
    request.setAttachment("## Current Chain: Demo (ID: abc-123-def)\n");

    assertEquals(
        "abc-123-def", extractor.resolveChainId(request, CONVERSATION_ID).orElseThrow());
  }

  /**
   * A conversation that built a chain has that chain in context from then on, even with nothing
   * attached: "now drop the audit step" is a change to what was just built, not a new integration.
   */
  @Test
  void findsTheChainTheConversationsCreateRunBuilt() {
    ProductPipelineRunStore runStore = mock(ProductPipelineRunStore.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    RunSnapshot snapshot = mock(RunSnapshot.class);
    ProductPipelineRunDocument document = mock(ProductPipelineRunDocument.class);
    Revision revision = mock(Revision.class);
    when(document.run()).thenReturn(snapshot);
    when(snapshot.runId()).thenReturn("run-1");
    when(runStore.loadByConversation(CONVERSATION_ID)).thenReturn(Optional.of(document));
    when(artifactStore.latest("run-1", Kind.MATERIALIZATION_RESULT)).thenReturn(Optional.of(revision));
    when(artifactStore.payload(revision, MaterializationResult.class))
        .thenReturn(
            new MaterializationResult(1, "chain-built-here", null, null, null, null, null));

    ChainContextExtractor extractor =
        new ChainContextExtractor(
            new com.fasterxml.jackson.databind.ObjectMapper(), runStore, artifactStore);

    assertEquals(
        "chain-built-here", extractor.resolveChainId(new ChatRequest(), CONVERSATION_ID).orElseThrow());
  }

  /** A run still drafting has written no chain, so there is nothing to be in context. */
  @Test
  void findsNoChainWhileTheCreateRunIsStillDrafting() {
    ProductPipelineRunStore runStore = mock(ProductPipelineRunStore.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    RunSnapshot snapshot = mock(RunSnapshot.class);
    ProductPipelineRunDocument document = mock(ProductPipelineRunDocument.class);
    when(document.run()).thenReturn(snapshot);
    when(snapshot.runId()).thenReturn("run-1");
    when(runStore.loadByConversation(CONVERSATION_ID)).thenReturn(Optional.of(document));
    when(artifactStore.latest("run-1", Kind.MATERIALIZATION_RESULT)).thenReturn(Optional.empty());

    ChainContextExtractor extractor =
        new ChainContextExtractor(
            new com.fasterxml.jackson.databind.ObjectMapper(), runStore, artifactStore);

    assertTrue(extractor.resolveChainId(new ChatRequest(), CONVERSATION_ID).isEmpty());
  }

  private static ChainContextExtractor newExtractor() {
    // No CREATE run behind this conversation: the attachment is the only source under test.
    return new ChainContextExtractor(new com.fasterxml.jackson.databind.ObjectMapper(), null, null);
  }
}
