package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ToolSession;

class RequirementDiscoveryKnowledgeToolTest {

  @Test
  void retrievesBoundedContextFromTheConversationPackage() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeContextProvider contextProvider = mock(KnowledgeContextProvider.class);
    KnowledgePackageRef ref = packageRef("sha256:pinned");
    KnowledgeQueryContext context = new KnowledgeQueryContext(ref);
    when(contextProvider.forConversation("conv-knowledge")).thenReturn(context);
    when(client.context(any(), any()))
        .thenReturn(
            new KnowledgeContextPackage(
                new KnowledgeResponseIdentity(ref),
                List.of("golden-patterns"),
                List.of(),
                0));
    RequirementDiscoveryKnowledgeTool tool =
        new RequirementDiscoveryKnowledgeTool(client, contextProvider);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-knowledge")) {
      result = tool.searchRequirementKnowledge("When should this integration use Kafka?");
    }

    ArgumentCaptor<KnowledgeContextRequest> request =
        ArgumentCaptor.forClass(KnowledgeContextRequest.class);
    verify(client).context(eq(context), request.capture());
    assertEquals("When should this integration use Kafka?", request.getValue().requestText());
    assertEquals("requirement-discovery", request.getValue().capabilityId());
    assertEquals("DISCOVERY", request.getValue().phase());
    assertEquals(8, request.getValue().maxObjects());
    assertEquals(12_000, request.getValue().maxChars());
    assertTrue(result.contains("fixture@1.0.0"), result);
    assertTrue(result.contains("sha256:pinned"), result);
  }

  @Test
  void reportsKnowledgeFailureWithoutThrowingOrInventingPlatformBehavior() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeContextProvider contextProvider = mock(KnowledgeContextProvider.class);
    when(contextProvider.forConversation("conv-knowledge"))
        .thenReturn(new KnowledgeQueryContext(packageRef("sha256:pinned")));
    when(client.context(any(), any()))
        .thenThrow(
            new KnowledgeClientException(
                KnowledgeFailureKind.KNOWLEDGE_TEMPORARILY_UNAVAILABLE,
                "sidecar temporarily unavailable"));
    RequirementDiscoveryKnowledgeTool tool =
        new RequirementDiscoveryKnowledgeTool(client, contextProvider);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-knowledge")) {
      result = tool.searchRequirementKnowledge("Does QIP support this pattern?");
    }

    assertTrue(result.contains("KNOWLEDGE_TEMPORARILY_UNAVAILABLE"), result);
    assertTrue(result.contains("Do not claim unsupported QIP behavior"), result);
  }

  private static KnowledgePackageRef packageRef(String checksum) {
    return new KnowledgePackageRef(
        "fixture@1.0.0",
        "1.0.0",
        "1.0.0",
        checksum,
        "CERTIFIED",
        "sha256:certificate");
  }
}
