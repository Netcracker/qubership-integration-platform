package org.qubership.integration.platform.ai.integration.catalog.lookup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Resolves one outbound call against the local catalog: Finder narrows services, Ranker scores
 * operations. Name substring search is not used.
 */
@ApplicationScoped
public class CatalogOperationLookup {

  private final CatalogSystemFinder finder;
  private final CatalogSystemReadTool catalogReadTool;

  @Inject
  public CatalogOperationLookup(
      CatalogSystemFinder finder, CatalogSystemReadTool catalogReadTool) {
    this.finder = Objects.requireNonNull(finder, "finder");
    this.catalogReadTool = Objects.requireNonNull(catalogReadTool, "catalogReadTool");
  }

  public CatalogLookupResult resolve(CatalogQuery query) {
    Objects.requireNonNull(query, "query");
    CatalogSystemFinder.Narrowed narrowed = finder.narrow(query);
    if (narrowed instanceof CatalogSystemFinder.Narrowed.TooBroad tooBroad) {
      return new CatalogLookupResult.TooBroad(tooBroad.candidateCount());
    }
    if (!(narrowed instanceof CatalogSystemFinder.Narrowed.Systems systems)
        || systems.systems().isEmpty()) {
      return new CatalogLookupResult.None();
    }
    List<CatalogMatch> known = new ArrayList<>();
    List<Scored> scored = score(query, systems.systems(), known);
    if (scored.isEmpty()) {
      if (known.isEmpty()) {
        return new CatalogLookupResult.None();
      }
      CatalogMatch sibling = uniqueNamedPartner(query, known);
      if (sibling != null) {
        return new CatalogLookupResult.Exact(sibling);
      }
      return new CatalogLookupResult.Ambiguous(ids(known));
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed());
    Scored leader = scored.getFirst();
    if (scored.size() == 1
        || leader.score() - scored.get(1).score() >= CatalogRanker.DECIDING_GAP) {
      return new CatalogLookupResult.Exact(leader.match());
    }
    List<String> tied = new ArrayList<>();
    for (Scored candidate : scored) {
      if (leader.score() - candidate.score() < CatalogRanker.DECIDING_GAP) {
        tied.add(candidate.match().integrationOperationId());
      }
    }
    return new CatalogLookupResult.Ambiguous(List.copyOf(tied));
  }

  private List<Scored> score(
      CatalogQuery query, List<CatalogRestClient.SystemDto> systems, List<CatalogMatch> known) {
    List<Scored> scored = new ArrayList<>();
    for (CatalogRestClient.SystemDto system : systems) {
      if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
        continue;
      }
      List<CatalogRestClient.SpecificationDto> specs =
          catalogReadTool.getApiSpecifications(system.id());
      for (CatalogRestClient.SpecificationDto spec : specs) {
        if (spec == null
            || CatalogStrings.blankToNull(spec.id()) == null
            || CatalogStrings.blankToNull(spec.specificationGroupId()) == null) {
          continue;
        }
        List<CatalogRestClient.OperationDto> operations =
            catalogReadTool.listCatalogOperations(spec.id(), system.id(), null);
        for (CatalogRestClient.OperationDto operation : operations) {
          if (operation == null || CatalogStrings.blankToNull(operation.id()) == null) {
            continue;
          }
          CatalogMatch match =
              new CatalogMatch(
                  system.id(),
                  spec.specificationGroupId(),
                  spec.id(),
                  operation.id(),
                  system.name(),
                  system.protocol(),
                  operation.method(),
                  operation.path(),
                  operation.name(),
                  "catalog-read:" + system.id() + "/" + spec.id() + "/" + operation.id());
          known.add(match);
          int score = CatalogRanker.score(query, system, operation);
          if (score < CatalogRanker.THRESHOLD) {
            continue;
          }
          scored.add(new Scored(score, match));
        }
      }
    }
    return scored;
  }

  /**
   * A payload command name is not a catalog key. When Ranker scored nothing, bind the one catalog
   * operation the same request already named.
   */
  private static CatalogMatch uniqueNamedPartner(CatalogQuery query, List<CatalogMatch> known) {
    CatalogMatch found = null;
    for (CatalogMatch match : known) {
      if (!namedInRequest(query, match.operationName())) {
        continue;
      }
      if (found != null) {
        return null;
      }
      found = match;
    }
    return found;
  }

  private static boolean namedInRequest(CatalogQuery query, String operationName) {
    String name = CatalogStrings.blankToNull(operationName);
    if (name == null) {
      return false;
    }
    for (String named : query.namedInRequest()) {
      if (named == null || named.isBlank()) {
        continue;
      }
      if (name.equalsIgnoreCase(named.trim()) || named.contains(name)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> ids(List<CatalogMatch> known) {
    List<String> ids = new ArrayList<>(known.size());
    for (CatalogMatch match : known) {
      ids.add(match.integrationOperationId());
    }
    return List.copyOf(ids);
  }

  private record Scored(int score, CatalogMatch match) {}
}
