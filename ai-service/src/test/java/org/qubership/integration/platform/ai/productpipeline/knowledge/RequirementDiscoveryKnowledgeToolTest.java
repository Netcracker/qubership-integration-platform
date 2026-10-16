package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.plan.RequirementDiscoveryDirective;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;

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
                List.of(knowledgeObject()),
                31));
    RequirementDraftStore draftStore = new RequirementDraftStore();
    RequirementDiscoveryKnowledgeTool tool =
        new RequirementDiscoveryKnowledgeTool(client, contextProvider, draftStore);

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
    assertEquals(
        RequirementDiscoveryDirective.STAY, draftStore.turnDirective("conv-knowledge"));
    assertTrue(result.contains("Loop repeats its child steps."), result);
    assertFalse(result.contains("fixture@1.0.0"), result);
    assertFalse(result.contains("sha256:pinned"), result);
    assertFalse(result.contains("CIP:GEN:element:loop"), result);
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
    RequirementDraftStore draftStore = new RequirementDraftStore();
    RequirementDiscoveryKnowledgeTool tool =
        new RequirementDiscoveryKnowledgeTool(client, contextProvider, draftStore);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-knowledge")) {
      result = tool.searchRequirementKnowledge("Does QIP support this pattern?");
    }

    assertTrue(result.contains("KNOWLEDGE_TEMPORARILY_UNAVAILABLE"), result);
    assertTrue(result.contains("Do not claim unsupported QIP behavior"), result);
    assertEquals(
        RequirementDiscoveryDirective.STAY, draftStore.turnDirective("conv-knowledge"));
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

  private static CanonicalKnowledgeObject knowledgeObject() {
    return new CanonicalKnowledgeObject(
        "1",
        "CIP:GEN:element:loop",
        "element",
        "Loop",
        "",
        Map.of(),
        List.of(),
        new CanonicalKnowledgeObject.Content(
            "markdown", "Loop repeats its child steps.", null, List.of()),
        "1",
        "ACTIVE",
        new CanonicalKnowledgeObject.Source(
            "markdown", "docs/elements/loop.md", "loop", "sha256:source", "1"));
  }
}
