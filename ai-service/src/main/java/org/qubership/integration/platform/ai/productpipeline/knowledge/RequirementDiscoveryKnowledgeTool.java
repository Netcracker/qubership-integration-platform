package org.qubership.integration.platform.ai.productpipeline.knowledge;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ToolSession;

/** Bounded knowledge-package lookup for conversational requirement discovery. */
@ApplicationScoped
public class RequirementDiscoveryKnowledgeTool {

  static final int MAX_OBJECTS = 8;
  static final int MAX_CHARS = 12_000;

  private final KnowledgeClient knowledgeClient;
  private final KnowledgeContextProvider contextProvider;

  @Inject
  public RequirementDiscoveryKnowledgeTool(
      KnowledgeClient knowledgeClient, KnowledgeContextProvider contextProvider) {
    this.knowledgeClient = Objects.requireNonNull(knowledgeClient, "knowledgeClient");
    this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
  }

  @Tool("""
      Retrieve relevant QIP platform knowledge for the user's current architecture or behavior
      question. Use this before making a QIP-specific capability, pattern, element, or constraint
      claim. Pass the user's concrete question. The server selects the conversation's pinned
      knowledge package and bounds the response. This tool is read-only and never changes
      requirements, catalog bindings, or imports.
      """)
  public String searchRequirementKnowledge(String question) {
    String conversationId = ToolSession.resolveConversationId();
    if (conversationId == null || conversationId.isBlank()) {
      return limitation(
          KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST,
          "No active conversation is bound to this lookup.");
    }
    if (question == null || question.isBlank()) {
      return limitation(
          KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST,
          "A concrete platform question is required.");
    }
    try {
      KnowledgeQueryContext context = contextProvider.forConversation(conversationId);
      KnowledgeContextPackage knowledge =
          knowledgeClient.context(
              context,
              new KnowledgeContextRequest(
                  question.trim(),
                  "requirement-discovery",
                  "DISCOVERY",
                  List.of(),
                  MAX_OBJECTS,
                  MAX_CHARS));
      return knowledge.renderMarkdown();
    } catch (KnowledgeClientException error) {
      return limitation(error.kind(), error.getMessage());
    }
  }

  private static String limitation(KnowledgeFailureKind kind, String detail) {
    return "QIP knowledge lookup unavailable ("
        + kind
        + "): "
        + detail
        + " Do not claim unsupported QIP behavior; state this limitation in the answer.";
  }
}
