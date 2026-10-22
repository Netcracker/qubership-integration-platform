package org.qubership.integration.platform.ai.integration.catalog.lookup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Narrows the catalog to the services worth reading operations from, using the catalog's own filter
 * endpoint.
 *
 * <p>The predicates are ordered by how much they cut, most selective first, and are sent as one AND
 * set. An empty answer drops the last predicate and asks again, so the number of requests is
 * bounded by the number of predicates rather than by how many words the author's service name
 * happens to contain. Dropping from the end keeps the operation path — the one predicate that
 * identifies a service by what it offers rather than by what it is called — until last.
 *
 * <p>{@code /v1/systems/filter} takes no limit, so a set that survives to the end can still be the
 * whole catalog. {@link Narrowed.TooBroad} says so rather than walking it: reading specifications
 * and operations for a few thousand services to score them is not a lookup, and the fix is one more
 * fact from the author, not more requests.
 */
@ApplicationScoped
public class CatalogSystemFinder {

  /** Past this many candidates, ask for another fact instead of reading them all. */
  static final int TOO_BROAD = 50;

  /** Shorter tokens match too many service names to narrow anything. */
  private static final int SIGNIFICANT_TOKEN_LENGTH = 4;

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogSystemFinder(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  /** What the catalog holds for a query, once narrowing has run. */
  public sealed interface Narrowed {

    /** Few enough services to read operations from. Possibly empty. */
    record Systems(List<CatalogRestClient.SystemDto> systems) implements Narrowed {}

    /** The query did not narrow the catalog enough to read from it. */
    record TooBroad(int candidateCount) implements Narrowed {}
  }

  public Narrowed narrow(CatalogQuery query) {
    Objects.requireNonNull(query, "query");
    List<CatalogSystemFilter> predicates = predicates(query);
    for (int size = predicates.size(); size > 0; size--) {
      List<CatalogRestClient.SystemDto> hits =
          catalogRestClient.filterSystems(List.copyOf(predicates.subList(0, size)));
      if (hits != null && !hits.isEmpty()) {
        return classify(hits);
      }
    }
    // Nothing left to filter by. The plain search also matches on service id and description, so it
    // still answers when the author pasted an id or wrote words only the description carries.
    String fallback = query.systemHint() == null ? query.operationHint() : query.systemHint();
    if (fallback == null) {
      return new Narrowed.Systems(List.of());
    }
    return classify(catalogRestClient.searchSystems(new CatalogSystemSearchRequest(fallback)));
  }

  /** Predicates for one query, most selective first. */
  static List<CatalogSystemFilter> predicates(CatalogQuery query) {
    List<CatalogSystemFilter> predicates = new ArrayList<>();
    if (query.path() != null) {
      predicates.add(CatalogSystemFilter.contains("URL", query.path()));
    }
    if (query.specificationHint() != null) {
      predicates.add(
          CatalogSystemFilter.contains("SPECIFICATION_GROUP", query.specificationHint()));
    }
    // Protocol is a ranker hint, not a filter. AND-ing PROTOCOL=http drops kafka services
    // such as om-order-lifecycle-manager-WFMS, then the empty answer drops NAME and the
    // retry walks every HTTP service.
    String token = longestSignificantToken(query.systemHint());
    if (token != null) {
      predicates.add(CatalogSystemFilter.contains("NAME", token));
    }
    return predicates;
  }

  /**
   * The one token of the service hint most likely to survive an import.
   *
   * <p>A hint is filtered by its longest token rather than whole: whole-name matching is what fails
   * on import-derived names, and every token would be a separate request for no more recall than
   * the ranker already provides.
   */
  static String longestSignificantToken(String systemHint) {
    String hint = CatalogStrings.blankToNull(systemHint);
    if (hint == null) {
      return null;
    }
    String longest = null;
    for (String token : hint.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (token.length() >= SIGNIFICANT_TOKEN_LENGTH
          && (longest == null || token.length() > longest.length())) {
        longest = token;
      }
    }
    return longest;
  }

  private static Narrowed classify(List<CatalogRestClient.SystemDto> systems) {
    if (systems == null) {
      return new Narrowed.Systems(List.of());
    }
    List<CatalogRestClient.SystemDto> identified =
        systems.stream()
            .filter(system -> system != null && CatalogStrings.blankToNull(system.id()) != null)
            .toList();
    return identified.size() > TOO_BROAD
        ? new Narrowed.TooBroad(identified.size())
        : new Narrowed.Systems(identified);
  }
}
