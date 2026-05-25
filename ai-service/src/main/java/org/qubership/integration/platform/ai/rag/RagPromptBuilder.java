package org.qubership.integration.platform.ai.rag;

import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;

/**
 * Static helper that eliminates the repeated "retrieve → format → prepend" pattern found across
 * scenario handlers.
 */
public final class RagPromptBuilder {

  private RagPromptBuilder() {}

  /**
   * Retrieves RAG context and prepends it to the user text with the given heading. If no relevant
   * context is found, returns {@code userText} unchanged.
   *
   * @param retriever the RAG retriever to query
   * @param query natural-language query for RAG retrieval
   * @param sourceFilter optional source filter ("docs", "schema", or null for all)
   * @param elementTypeFilter optional element-type filter (or null)
   * @param maxResults max number of chunks to retrieve
   * @param contextHeading heading text for the context section (without "## " prefix)
   * @param userText the user's message / prompt body
   * @return augmented prompt with RAG context prepended, or plain userText if no context
   */
  public static String augment(
      RagRetriever retriever,
      String query,
      String sourceFilter,
      String elementTypeFilter,
      int maxResults,
      String contextHeading,
      String userText) {
    String context =
        retriever.toContextString(
            retriever.retrieve(query, sourceFilter, elementTypeFilter, maxResults));
    if (context.isBlank()) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(userText);
    }
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(
        "## " + contextHeading + "\n\n" + context + "\n\n---\n\n" + userText);
  }
}
