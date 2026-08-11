package org.qubership.integration.platform.ai.chain.presentation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Loads {@link ChainCatalogFacts} from the runtime catalog (single REST boundary). */
@ApplicationScoped
public class ChainCatalogFactsService {

  private static final String LIFECYCLE_BUILT_IN_CATALOG = "built_in_catalog";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainCatalogFactsService(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  public ChainCatalogFacts load(String chainId) {
    Objects.requireNonNull(chainId, "chainId");
    if (chainId.isBlank()) {
      throw new IllegalArgumentException("chainId must not be blank");
    }

    CatalogRestClient.ChainDto chain = catalogRestClient.getChain(chainId);
    List<CatalogElementResponseDto> roots = catalogRestClient.listElements(chainId);
    List<CatalogDependencyDto> deps = catalogRestClient.listDependencies(chainId);

    List<ChainCatalogElement> elements = flattenElements(roots != null ? roots : List.of());
    List<ChainCatalogDependency> dependencies = mapDependencies(deps != null ? deps : List.of());

    String name = chain != null && chain.name() != null ? chain.name() : chainId;
    String description = chain != null ? chain.description() : null;

    return new ChainCatalogFacts(
        chainId,
        name,
        nullToEmpty(description),
        elements.size(),
        dependencies.size(),
        summarizeTrigger(elements),
        List.copyOf(elements),
        List.copyOf(dependencies),
        LIFECYCLE_BUILT_IN_CATALOG);
  }

  /** Deterministic English fallback when the chain presentation agent is unavailable. */
  public String formatFallbackSummary(ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");

    String chainLabel =
        facts.chainName() != null && !facts.chainName().isBlank()
            ? facts.chainName()
            : facts.chainId();

    StringBuilder sb = new StringBuilder();
    sb.append("Chain \"")
        .append(chainLabel)
        .append("\" (")
        .append(facts.chainId())
        .append("): ");
    sb.append(facts.elementCount()).append(" elements, ");
    sb.append(facts.dependencyCount()).append(" dependencies.");

    if (facts.triggerSummary() != null && !facts.triggerSummary().isBlank()) {
      sb.append(" Entry: ").append(facts.triggerSummary()).append(".");
    }

    String flowTypes = coreFlowTypes(facts);
    if (!flowTypes.isEmpty()) {
      sb.append(" Flow types: ").append(flowTypes).append(".");
    }

    return sb.toString();
  }

  static List<ChainCatalogElement> flattenElements(List<CatalogElementResponseDto> roots) {
    List<ChainCatalogElement> flat = new ArrayList<>();
    if (roots == null) {
      return flat;
    }
    for (CatalogElementResponseDto root : roots) {
      flattenElement(root, flat);
    }
    return List.copyOf(flat);
  }

  private static void flattenElement(CatalogElementResponseDto element, List<ChainCatalogElement> flat) {
    if (element == null || element.id == null) {
      return;
    }
    flat.add(toCatalogElement(element));
    if (element.children != null) {
      for (CatalogElementResponseDto child : element.children) {
        flattenElement(child, flat);
      }
    }
  }

  private static ChainCatalogElement toCatalogElement(CatalogElementResponseDto element) {
    Map<String, Object> props = element.properties != null ? element.properties : Map.of();
    return new ChainCatalogElement(
        element.id,
        nullToEmpty(element.type),
        labelFor(element),
        blankToNull(element.parentElementId),
        Map.copyOf(props));
  }

  private static List<ChainCatalogDependency> mapDependencies(List<CatalogDependencyDto> deps) {
    List<ChainCatalogDependency> mapped = new ArrayList<>();
    for (CatalogDependencyDto dep : deps) {
      if (dep == null || dep.from == null || dep.to == null) {
        continue;
      }
      mapped.add(new ChainCatalogDependency(dep.from, dep.to));
    }
    return mapped;
  }

  private static String summarizeTrigger(List<ChainCatalogElement> elements) {
    for (ChainCatalogElement element : elements) {
      if (ChainElementFamilies.isTrigger(element.type())) {
        return element.name() + " (" + element.type() + ")";
      }
    }
    return "";
  }

  private static String coreFlowTypes(ChainCatalogFacts facts) {
    return facts.elements().stream()
        .map(ChainCatalogElement::type)
        .filter(type -> type != null && !type.isBlank())
        .distinct()
        .sorted()
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
  }

  private static String labelFor(CatalogElementResponseDto element) {
    if (element.name != null && !element.name.isBlank()) {
      return element.name;
    }
    if (element.type != null && !element.type.isBlank()) {
      return element.type;
    }
    return element.id;
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
