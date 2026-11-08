package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Server-issued permission to search API Hub for one unresolved service call.
 *
 * <p>An API Hub search is a side effect on a system outside this one, and until now any wording in
 * a prompt could start one. The server issues an authorization only where it has established a
 * catalog miss, so the search tool can tell an intended search from an improvised one.
 *
 * <p>The authorization names the service call it was issued for and carries a query budget. The
 * budget bounds semantic search iterations — how many times the model may reword the query — and is
 * unrelated to transport retries inside the MCP client.
 */
@ApplicationScoped
public class ApiHubSearchAuthorizations {

  /**
   * Query budget for one authorization.
   *
   * <p>This is a constant, not a setting. Three rewordings are enough to distinguish a naming
   * mismatch from a missing API; make it configurable when observed usage requires another limit.
   */
  static final int DEFAULT_QUERY_BUDGET = 3;

  /** One scoped permission to search API Hub. */
  public record Authorization(
      String serviceCallId,
      String capabilityQuery,
      String scope,
      String reason,
      int remainingQueries) {

    Authorization spend() {
      return new Authorization(
          serviceCallId, capabilityQuery, scope, reason, remainingQueries - 1);
    }
  }

  public static final String DISABLED_MESSAGE = "API Hub is disabled for this request.";
  private static final String API_HUB = "(?:the\\h+)?api[\\h_-]*hub";
  private static final Pattern CATALOG_ONLY = Pattern.compile(
      "(?iu)\\b(?:no|without|do not (?:use|search|call|query)|don't (?:use|search|call|query)"
          + "|never (?:use|search|call|query)|без|не (?:используй|использовать|вызывай|вызывать))\\h+"
          + API_HUB + "\\b|\\b(?:runtime[\\h_-]+catalog[\\h_-]+only|only\\h+(?:the\\h+)?runtime\\h+catalog)\\b");
  private static final Pattern API_HUB_ALLOWED = Pattern.compile(
      "(?iu)(?:^|[.!?]|\\R)\\h*(?:now\\h+)?(?:please\\h+)?"
          + "(?:use|search|call|query|you (?:may|can) (?:use|search)|i authorize (?:using|searching))\\h+"
          + API_HUB + "\\b");
  private final ConversationService conversations;

  public ApiHubSearchAuthorizations() {
    this(null);
  }

  @Inject
  public ApiHubSearchAuthorizations(ConversationService conversations) {
    this.conversations = conversations;
  }

  public boolean prohibitsApiHub(String conversationId) {
    if (conversations == null || CatalogStrings.blankToNull(conversationId) == null) return false;
    for (var turn : conversations.archive().read(conversationId).activeTurns().reversed()) {
      String text = turn.rawText();
      if (turn.message().role() != ConversationMessage.Role.USER || text == null) continue;
      if (CATALOG_ONLY.matcher(text).find()) return true;
      if (API_HUB_ALLOWED.matcher(text).find()) return false;
    }
    return false;
  }

  private final Map<String, Authorization> byConversation = new ConcurrentHashMap<>();

  /** Authorizes one scoped search run after a confirmed catalog miss. */
  public Authorization issue(
      String conversationId, String serviceCallId, String capabilityQuery, String reason) {
    Authorization authorization =
        new Authorization(
            serviceCallId, capabilityQuery, "apihub-search", reason, DEFAULT_QUERY_BUDGET);
    String id = CatalogStrings.blankToNull(conversationId);
    if (id != null) {
      byConversation.put(id, authorization);
    }
    return authorization;
  }

  /**
   * Spends one query of the active authorization, or reports that there is none.
   *
   * <p>An exhausted authorization is dropped rather than kept at zero, so an unavailable API cannot
   * hold a conversation in a search loop.
   */
  public Optional<Authorization> consume(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return Optional.empty();
    }
    if (prohibitsApiHub(id)) {
      revoke(id);
      return Optional.empty();
    }
    Authorization authorization = byConversation.get(id);
    if (authorization == null || authorization.remainingQueries() <= 0) {
      byConversation.remove(id);
      return Optional.empty();
    }
    Authorization spent = authorization.spend();
    if (spent.remainingQueries() <= 0) {
      byConversation.remove(id);
    } else {
      byConversation.put(id, spent);
    }
    return Optional.of(spent);
  }

  public Optional<Authorization> active(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    return id == null ? Optional.empty() : Optional.ofNullable(byConversation.get(id));
  }

  public void revoke(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id != null) {
      byConversation.remove(id);
    }
  }
}
