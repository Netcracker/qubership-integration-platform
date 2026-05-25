package org.qubership.integration.platform.ai.chat.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRuntimePromptNote;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.rag.RagPromptBuilder;
import org.qubership.integration.platform.ai.rag.RagRetriever;

/**
 * Central user-prompt assembly for LangChain4j agents (fixed block order per {@link
 * PromptProfile}).
 */
@ApplicationScoped
public class ConversationPromptAssembler {

  private static final String SECTION_SEPARATOR = "\n\n---\n\n";
  private static final int RAG_QUERY_MAX_CHARS = 800;

  @Inject RagRetriever ragRetriever;

  @Inject AuthoritativeStateSection authoritativeStateSection;

  @Inject ConversationPlanningDiaryService planningDiaryService;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Inject ApiHubRuntimePromptNote apiHubRuntimePromptNote;

  public String assemble(PromptAssemblyRequest assemblyRequest) {
    PromptProfile profile = assemblyRequest.profile();
    ChatRequest request = assemblyRequest.request();

    if (profile == PromptProfile.MINIMAL) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(request.getEffectiveUserText());
    }

    String body = assembleBody(assemblyRequest);
    if (profile.usesRag()) {
      return RagPromptBuilder.augment(
          ragRetriever,
          ragQueryFromEffectiveText(request),
          profile.ragSource(),
          profile.ragElementTypeFilter(),
          profile.ragMaxResults(),
          profile.ragHeading(),
          body);
    }
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }

  /** Body blocks without RAG (used by tests and {@link #assemble}). */
  String assembleBody(PromptAssemblyRequest assemblyRequest) {
    PromptProfile profile = assemblyRequest.profile();
    ChatRequest request = assemblyRequest.request();
    String conversationId = assemblyRequest.conversationId();

    String userBlock = buildUserBlock(profile, request);
    if (profile.includeApiHubNote()) {
      userBlock = apiHubRuntimePromptNote.maybePrefix(userBlock);
    }

    String body = userBlock;
    if (profile.includePlanAppendix()) {
      body = prependIfNonBlank(activeChainPlanService.formatPromptAppendix(conversationId), body);
    }
    if (profile.includeDiary()) {
      body = prependIfNonBlank(planningDiaryService.formatAppendix(conversationId), body);
    }
    if (profile.includeAuthoritativeState()) {
      body = prependIfNonBlank(authoritativeStateSection.format(conversationId), body);
    }
    return body;
  }

  private static String buildUserBlock(PromptProfile profile, ChatRequest request) {
    String text = request.getEffectiveUserText();
    if (profile.userRequestHeader()) {
      return "## User Request\n\n" + text;
    }
    return text;
  }

  /**
   * RAG query from user message plus inlined attachment head (IDS title / intent), not message
   * alone.
   */
  static String ragQueryFromEffectiveText(ChatRequest request) {
    String effective = request.getEffectiveUserText();
    if (effective == null || effective.isBlank()) {
      String message = request.getMessage();
      return message != null ? message : "";
    }
    String head = effective;
    int sep = effective.indexOf("\n\n---\n\n");
    if (sep > 0) {
      head = effective.substring(0, sep);
    }
    head = head.strip();
    if (head.length() > RAG_QUERY_MAX_CHARS) {
      head = head.substring(0, RAG_QUERY_MAX_CHARS);
    }
    return head.isBlank() ? (request.getMessage() != null ? request.getMessage() : "") : head;
  }

  private static String prependIfNonBlank(String prefix, String body) {
    if (prefix == null || prefix.isBlank()) {
      return body;
    }
    if (body == null || body.isBlank()) {
      return prefix;
    }
    return prefix + SECTION_SEPARATOR + body;
  }
}
