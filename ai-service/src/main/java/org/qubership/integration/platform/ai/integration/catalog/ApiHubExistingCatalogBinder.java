package org.qubership.integration.platform.ai.integration.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;

/**
 * Resolves an API Hub candidate against systems already present in the runtime catalog so gather /
 * import can bind without a redundant import when the service was imported earlier.
 */
@ApplicationScoped
public class ApiHubExistingCatalogBinder {

  private static final Logger LOG = Logger.getLogger(ApiHubExistingCatalogBinder.class);

  private final CatalogSystemReadTool catalogReadTool;
  private final ConversationCatalogCache catalogCache;

  @Inject
  public ApiHubExistingCatalogBinder(
      CatalogSystemReadTool catalogReadTool, ConversationCatalogCache catalogCache) {
    this.catalogReadTool = Objects.requireNonNull(catalogReadTool, "catalogReadTool");
    this.catalogCache = catalogCache;
  }

  /**
   * Returns a catalog binding when exactly one matching system/spec/operation hierarchy is found
   * for {@code refs}; empty when nothing matches or the match is ambiguous.
   */
  public Optional<ResolvedCatalogBinding> resolve(
      String conversationId, ApiHubRequirementRefs refs) {
    if (refs == null || !refs.hasImportableRefs()) {
      return Optional.empty();
    }
    List<CatalogRestClient.SystemDto> systems = searchCandidateSystems(refs);
    if (systems.isEmpty()) {
      return Optional.empty();
    }

    String wantedOperationId = CatalogStrings.blankToNull(refs.operationId());
    List<ResolvedCatalogBinding> matches = new ArrayList<>();

    for (CatalogRestClient.SystemDto system : systems) {
      if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
        continue;
      }
      if (!systemNameAgrees(refs, system.name())) {
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
        if (!specificationGroupAgrees(refs, system.id(), spec)) {
          continue;
        }
        String operationId =
            resolveOperationId(system.id(), spec.id(), wantedOperationId);
        matches.add(
            new ResolvedCatalogBinding(
                system.id(),
                spec.id(),
                spec.specificationGroupId(),
                operationId,
                CatalogStrings.blankToNull(system.type())));
      }
    }

    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.size() > 1) {
      LOG.infof(
          "ApiHubExistingCatalogBinder: ambiguous catalog matches conversationId=%s count=%s"
              + " packageId=%s",
          conversationId, matches.size(), refs.packageId());
      return Optional.empty();
    }

    ResolvedCatalogBinding binding =
        ResolvedCatalogBinding.enrichFromCache(catalogCache, conversationId, matches.getFirst());
    if (conversationId != null
        && !conversationId.isBlank()
        && catalogCache != null
        && !systems.isEmpty()) {
      catalogCache.rememberSystems(conversationId, systems);
      catalogCache.rememberActiveSystemId(conversationId, binding.systemId());
    }
    LOG.infof(
        "ApiHubExistingCatalogBinder: bound existing catalog system conversationId=%s"
            + " systemId=%s specId=%s packageId=%s",
        conversationId, binding.systemId(), binding.specificationId(), refs.packageId());
    return Optional.of(binding);
  }

  private List<CatalogRestClient.SystemDto> searchCandidateSystems(ApiHubRequirementRefs refs) {
    LinkedHashSet<String> seenIds = new LinkedHashSet<>();
    List<CatalogRestClient.SystemDto> out = new ArrayList<>();
    for (String term : searchTerms(refs)) {
      for (CatalogRestClient.SystemDto system : catalogReadTool.searchCatalogSystems(term)) {
        if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
          continue;
        }
        if (seenIds.add(system.id())) {
          out.add(system);
        }
      }
    }
    return out;
  }

  static List<String> searchTerms(ApiHubRequirementRefs refs) {
    LinkedHashSet<String> terms = new LinkedHashSet<>();
    addTerm(terms, refs.packageName());
    addTerm(terms, refs.catalogSystemName());
    String packageId = CatalogStrings.blankToNull(refs.packageId());
    if (packageId != null) {
      addTerm(terms, packageId.trim().replace('.', ' '));
      String[] parts = packageId.split("\\.");
      for (String part : parts) {
        addTerm(terms, part);
      }
      if (parts.length > 0) {
        addTerm(terms, parts[parts.length - 1]);
      }
    }
    return List.copyOf(terms);
  }

  private static void addTerm(Set<String> terms, String raw) {
    String term = CatalogStrings.blankToNull(raw);
    if (term == null || term.length() < 3) {
      return;
    }
    terms.add(term);
  }

  static boolean systemNameAgrees(ApiHubRequirementRefs refs, String catalogName) {
    if (CatalogStrings.blankToNull(catalogName) == null) {
      return false;
    }
    String actual = catalogName.trim().toLowerCase(Locale.ROOT);
    for (String term : searchTerms(refs)) {
      String required = term.toLowerCase(Locale.ROOT);
      if (actual.contains(required) || required.contains(actual)) {
        return true;
      }
    }
    // Prefer packageId segments over generic human-name words ("Management").
    String packageId = CatalogStrings.blankToNull(refs.packageId());
    if (packageId != null) {
      for (String part : packageId.split("\\.")) {
        String token = part.toLowerCase(Locale.ROOT);
        if (token.length() >= 5 && actual.contains(token)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean specificationGroupAgrees(
      ApiHubRequirementRefs refs, String systemId, CatalogRestClient.SpecificationDto spec) {
    LinkedHashSet<String> groupNames = new LinkedHashSet<>();
    addTerm(groupNames, refs.specificationGroupName());
    addTerm(groupNames, refs.version());
    addTerm(groupNames, refs.packageName());
    addTerm(groupNames, refs.catalogSystemName());
    for (String groupName : groupNames) {
      if (ApiHubSpecificationImportService.belongsToSpecificationGroup(
          spec, systemId, groupName)) {
        return true;
      }
    }
    return false;
  }

  private String resolveOperationId(
      String systemId, String specificationId, String wantedOperationId) {
    List<CatalogRestClient.OperationDto> ops =
        catalogReadTool.listCatalogOperations(specificationId, systemId, null);
    if (ops == null || ops.isEmpty()) {
      return wantedOperationId;
    }
    if (wantedOperationId == null) {
      return ops.size() == 1 ? ops.getFirst().id() : null;
    }
    for (CatalogRestClient.OperationDto op : ops) {
      if (op != null && wantedOperationId.equals(op.id())) {
        return op.id();
      }
    }
    String wantedLower = wantedOperationId.toLowerCase(Locale.ROOT);
    for (CatalogRestClient.OperationDto op : ops) {
      if (op == null) {
        continue;
      }
      String name = CatalogStrings.blankToNull(op.name());
      String path = CatalogStrings.blankToNull(op.path());
      if ((name != null && name.toLowerCase(Locale.ROOT).contains("search")
              && wantedLower.contains("search"))
          || (path != null && wantedLower.contains("search") && path.contains("/search"))) {
        return op.id();
      }
    }
    return wantedOperationId;
  }
}
