package org.qubership.integration.platform.ai.productpipeline.knowledge;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.RequirementDiscoveryDirective;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;

/** Bounded knowledge-package lookup for conversational requirement discovery. */
@ApplicationScoped
public class RequirementDiscoveryKnowledgeTool {

  private static final Logger LOG = Logger.getLogger(RequirementDiscoveryKnowledgeTool.class);
  private static final String TOOL_NAME = "searchRequirementKnowledge";
  static final int MAX_OBJECTS = 8;
  static final int MAX_CHARS = 12_000;

  private final KnowledgeClient knowledgeClient;
  private final KnowledgeContextProvider contextProvider;
  private final RequirementDraftStore draftStore;

  @Inject
  public RequirementDiscoveryKnowledgeTool(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider contextProvider,
      RequirementDraftStore draftStore) {
    this.knowledgeClient = Objects.requireNonNull(knowledgeClient, "knowledgeClient");
    this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
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
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL_NAME, conversationId, "question=" + question);
    if (conversationId == null || conversationId.isBlank()) {
      return complete(
          conversationId,
          startMs,
          limitation(
              KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST,
              "No active conversation is bound to this lookup."));
    }
    // A knowledge lookup is a consultation turn. An explicit CONTINUE call later in the same turn
    // can override this safe default.
    draftStore.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
    if (question == null || question.isBlank()) {
      return complete(
          conversationId,
          startMs,
          limitation(
              KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST,
              "A concrete platform question is required."));
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
      KnowledgePackageRef sourcePackage = knowledge.identity().packageRef();
      LOG.infof(
          "Knowledge sources selected: conversationId=%s, packageKey=%s, packageChecksum=%s, objectIds=%s",
          conversationId,
          sourcePackage.packageKey(),
          sourcePackage.packageChecksum(),
          knowledge.objects().stream().map(CanonicalKnowledgeObject::id).toList());
      return complete(conversationId, startMs, renderAnswerContext(knowledge));
    } catch (KnowledgeClientException error) {
      return complete(conversationId, startMs, limitation(error.kind(), error.getMessage()));
    } catch (RuntimeException error) {
      ToolTraceLog.logToolFailed(
          LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, error);
      throw error;
    }
  }

  private static String complete(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private static String limitation(KnowledgeFailureKind kind, String detail) {
    return "QIP knowledge lookup unavailable ("
        + kind
        + "): "
        + detail
        + " Do not claim unsupported QIP behavior; state this limitation in the answer.";
  }

  private static String renderAnswerContext(KnowledgeContextPackage knowledge) {
    String context =
        knowledge.objects().stream()
            .map(CanonicalKnowledgeObject::content)
            .map(CanonicalKnowledgeObject.Content::body)
            .filter(body -> body != null && !body.isBlank())
            .map(String::strip)
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse("");
    return context.isBlank()
        ? "No matching QIP knowledge content was returned."
        : "QIP knowledge context:\n\n" + context;
  }
}
