package org.qubership.integration.platform.ai.integration.catalog.lookup;

import java.util.Locale;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Scores one catalog operation against a {@link CatalogQuery}.
 *
 * <p>Scoring replaces the chain of name gates this lookup used to run, where a service whose
 * catalog name did not contain the author's wording was dropped before its operations were ever
 * read. Names written by people and names produced by specification imports disagree routinely, so
 * a gate on either one turns a present operation into a catalog miss, and a catalog miss is what
 * sends the caller off to import a specification the catalog already holds.
 *
 * <p>The weights encode one rule: the operation decides. Operation identity alone clears
 * {@link #THRESHOLD}; service name and protocol agreement never can, however well they read. They
 * only separate operations that already match.
 *
 * <p>The specification hint carries no weight here. It does its work in {@link CatalogSystemFinder},
 * where it narrows by specification-group name — a name this API does not return.
 */
public final class CatalogRanker {

  /** The operation is the one asked for: name matches exactly, or method and path both match. */
  static final int OPERATION_IDENTITY = 100;

  /** The operation name contains the hint, or the hint contains it. */
  static final int OPERATION_NAME_OVERLAP = 40;

  /** Full weight when every significant token of the service hint appears in the catalog name. */
  static final int SYSTEM_NAME_OVERLAP = 30;

  static final int PROTOCOL = 10;

  /** Below this, no operation is a match: nothing but operation identity or overlap reaches it. */
  public static final int THRESHOLD = OPERATION_NAME_OVERLAP;

  /** Two leaders closer than this are a tie, and a tie is a question for the author. */
  public static final int DECIDING_GAP = 20;

  /** Tokens shorter than this match too much to count as evidence. */
  private static final int SIGNIFICANT_TOKEN_LENGTH = 3;

  private CatalogRanker() {}

  public static int score(
      CatalogQuery query,
      CatalogRestClient.SystemDto system,
      CatalogRestClient.OperationDto operation) {
    if (operation == null) {
      return 0;
    }
    int score = operationScore(query, operation);
    if (score == 0) {
      return 0;
    }
    score += systemNameScore(query.systemHint(), system == null ? null : system.name());
    if (query.protocol() != null
        && system != null
        && query.protocol().equalsIgnoreCase(CatalogStrings.blankToNull(system.protocol()))) {
      score += PROTOCOL;
    }
    return score;
  }

  private static int operationScore(CatalogQuery query, CatalogRestClient.OperationDto operation) {
    if (query.hasMethodAndPath()
        && query.method().equalsIgnoreCase(CatalogStrings.blankToNull(operation.method()))
        && samePath(query.path(), operation.path())) {
      return OPERATION_IDENTITY;
    }
    String hint = normalize(query.operationHint());
    String name = normalize(operation.name());
    if (hint == null || name == null) {
      return 0;
    }
    if (hint.equals(name)) {
      return OPERATION_IDENTITY;
    }
    if (hint.length() >= SIGNIFICANT_TOKEN_LENGTH && (name.contains(hint) || hint.contains(name))) {
      return OPERATION_NAME_OVERLAP;
    }
    return 0;
  }

  /**
   * The share of the service hint's significant tokens that the catalog name carries.
   *
   * <p>Partial agreement scores partially: {@code om-order-lifecycle-manager async} shares three of
   * its four tokens with {@code om-order-lifecycle-manager-WFMS}, which is a strong signal, while
   * neither name contains the other.
   */
  private static int systemNameScore(String systemHint, String catalogName) {
    String hint = CatalogStrings.blankToNull(systemHint);
    String name = normalize(catalogName);
    if (hint == null || name == null) {
      return 0;
    }
    int significant = 0;
    int hits = 0;
    for (String token : hint.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (token.length() < SIGNIFICANT_TOKEN_LENGTH) {
        continue;
      }
      significant++;
      if (name.contains(token)) {
        hits++;
      }
    }
    return significant == 0 ? 0 : SYSTEM_NAME_OVERLAP * hits / significant;
  }

  private static boolean samePath(String required, String actual) {
    String left = trimTrailingSlash(CatalogStrings.blankToNull(required));
    String right = trimTrailingSlash(CatalogStrings.blankToNull(actual));
    return left != null && left.equalsIgnoreCase(right);
  }

  private static String trimTrailingSlash(String path) {
    if (path == null) {
      return null;
    }
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /** Lower-cases and drops the separators that distinguish written names from imported ones. */
  private static String normalize(String value) {
    String trimmed = CatalogStrings.blankToNull(value);
    if (trimmed == null) {
      return null;
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", "");
    return normalized.isEmpty() ? null : normalized;
  }
}
