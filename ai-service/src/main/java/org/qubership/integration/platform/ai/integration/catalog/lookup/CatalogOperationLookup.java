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
      return new CatalogLookupResult.Ambiguous(ids(known));
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed());
    Scored leader = scored.getFirst();
    if (scored.size() == 1
        || leader.score() - scored.get(1).score() >= CatalogRanker.DECIDING_GAP) {
      return new CatalogLookupResult.Exact(leader.match());
    }
    List<Scored> tied = new ArrayList<>();
    for (Scored candidate : scored) {
      if (leader.score() - candidate.score() < CatalogRanker.DECIDING_GAP) {
        tied.add(candidate);
      }
    }
    Scored chosen = namedTiedOperation(query, tied);
    if (chosen != null) {
      return new CatalogLookupResult.Exact(chosen.match());
    }
    Scored titleMatch = specNamedLikeSystem(tied);
    if (titleMatch != null) {
      return new CatalogLookupResult.Exact(titleMatch.match());
    }
    List<String> tiedIds = new ArrayList<>(tied.size());
    for (Scored candidate : tied) {
      tiedIds.add(candidate.match().integrationOperationId());
    }
    return new CatalogLookupResult.Ambiguous(List.copyOf(tiedIds));
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
          scored.add(new Scored(score, match, spec.name()));
        }
      }
    }
    return scored;
  }

  private static List<String> ids(List<CatalogMatch> known) {
    List<String> ids = new ArrayList<>(known.size());
    for (CatalogMatch match : known) {
      ids.add(match.integrationOperationId());
    }
    return List.copyOf(ids);
  }

  /**
   * The author already named one of the tied catalog operation ids. Bind that match the same way
   * an Exact lookup would.
   */
  private static Scored namedTiedOperation(CatalogQuery query, List<Scored> tied) {
    Scored match = null;
    for (Scored candidate : tied) {
      String operationId =
          CatalogStrings.blankToNull(candidate.match().integrationOperationId());
      if (operationId == null || !queryNamesOperationId(query, operationId)) {
        continue;
      }
      if (match != null) {
        return null;
      }
      match = candidate;
    }
    return match;
  }

  private static boolean queryNamesOperationId(CatalogQuery query, String operationId) {
    if (containsOperationId(query.specificationHint(), operationId)
        || containsOperationId(query.operationHint(), operationId)) {
      return true;
    }
    for (String named : query.namedInRequest()) {
      if (containsOperationId(named, operationId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsOperationId(String text, String operationId) {
    return text != null && text.contains(operationId);
  }

  /**
   * When two equally scored operations sit on specs of the same system, keep the spec whose name
   * matches the catalog system. A leftover filename-derived spec is not a second createTask.
   */
  private static Scored specNamedLikeSystem(List<Scored> tied) {
    Scored match = null;
    for (Scored candidate : tied) {
      String specName = CatalogStrings.blankToNull(candidate.specName());
      String systemName = CatalogStrings.blankToNull(candidate.match().systemName());
      if (specName == null || systemName == null || !specName.equalsIgnoreCase(systemName)) {
        continue;
      }
      if (match != null) {
        return null;
      }
      match = candidate;
    }
    return match;
  }

  private record Scored(int score, CatalogMatch match, String specName) {}
}
