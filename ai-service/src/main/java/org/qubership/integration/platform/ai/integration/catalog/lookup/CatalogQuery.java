package org.qubership.integration.platform.ai.integration.catalog.lookup;

import java.util.Locale;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * What is known about one outbound service call, in the fields a catalog lookup can narrow by.
 *
 * <p>Every field is optional, and every field is a hint written by a person or a model rather than
 * a value read out of the catalog. Catalog service names come from imported specifications and
 * seldom read like the name a person uses for the same service: {@code Party Management} is filed
 * as {@code S ProdCat PartyMgmt}, and {@code om-order-lifecycle-manager async} is filed as {@code
 * om-order-lifecycle-manager-WFMS}. So a hint is evidence to weigh, never a filter to reject by —
 * see {@link CatalogRanker}.
 *
 * @param systemHint service name as the author wrote it
 * @param specificationHint specification or specification-group name, when the author named one
 * @param protocol {@code http}, {@code kafka}, {@code amqp}, when the design settles it
 * @param method HTTP method, upper-cased
 * @param path HTTP path
 * @param operationHint operation name, or the raw operation query when no name was given
 * @param release specification version the author requires, for example {@code 2024.4}
 */
public record CatalogQuery(
    String systemHint,
    String specificationHint,
    String protocol,
    String method,
    String path,
    String operationHint,
    String release) {

  public CatalogQuery {
    systemHint = CatalogStrings.blankToNull(systemHint);
    specificationHint = CatalogStrings.blankToNull(specificationHint);
    protocol = lowerOrNull(protocol);
    method = upperOrNull(method);
    path = CatalogStrings.blankToNull(path);
    operationHint = CatalogStrings.blankToNull(operationHint);
    release = CatalogStrings.blankToNull(release);
  }

  /** True when method and path together identify the operation, so the name need not. */
  public boolean hasMethodAndPath() {
    return method != null && path != null;
  }

  private static String lowerOrNull(String value) {
    String trimmed = CatalogStrings.blankToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  private static String upperOrNull(String value) {
    String trimmed = CatalogStrings.blankToNull(value);
    return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
  }
}
