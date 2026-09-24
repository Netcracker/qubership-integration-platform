package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchHitParser;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool;

/**
 * Runtime-catalog lookup stays on {@link CatalogOperationLookup}. APIHub search reads the JSON
 * {@link CatalogFirstApiHubDiscoveryTool#resolveApiOperation} actually returns. This seam does not
 * import a contract.
 */
public final class ResolveApiOperationSeam implements CatalogResolution {

  private final CatalogOperationLookup catalog;
  private final CatalogFirstApiHubDiscoveryTool discovery;
  private final ObjectMapper json = new ObjectMapper();

  public ResolveApiOperationSeam(
      CatalogOperationLookup catalog, CatalogFirstApiHubDiscoveryTool discovery) {
    this.catalog = catalog;
    this.discovery = discovery;
  }

  @Override
  public CatalogLookup lookup(String operationHint, String pinnedVersion) {
    String release = pinnedVersion == null ? "" : pinnedVersion;
    CatalogLookupResult result =
        catalog.resolve(
            new CatalogQuery("", "", "", "", "", operationHint, release, List.of()));
    if (result instanceof CatalogLookupResult.Exact exact) {
      return new CatalogLookup.Hit(hit(exact.match(), operationHint, catalogVersion(exact.match(), release)));
    }
    if (result instanceof CatalogLookupResult.Ambiguous ambiguous) {
      return new CatalogLookup.Ambiguous(ambiguous.candidateIds());
    }
    if (!release.isBlank()) {
      return new CatalogLookup.Miss();
    }
    return new CatalogLookup.Miss();
  }

  @Override
  public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
    String release = pinnedVersion == null ? "" : pinnedVersion;
    String response =
        discovery.resolveApiOperation(
            interactionId == null ? "" : interactionId, "", "", operationHint, "", release);
    return parse(response, operationHint, release);
  }

  @Override
  public void importContract(ApiHubHit hit) {
    // Chain creation imports the pinned contract. Binding keeps the APIHub reference only.
  }

  /**
   * Reads a {@code resolveApiOperation} payload. Catalog hits expose {@code catalogBinding}.
   * APIHub hits expose {@code operations} or {@code items} with {@code version} on each hit.
   * An empty pin is not a version.
   */
  ApiHubHit parse(String response, String hint, String pinnedVersion) {
    if (response == null || response.isBlank()) {
      return null;
    }
    try {
      JsonNode tree = json.readTree(response);
      if ("ERROR".equals(tree.path("status").asText()) || "CATALOG_MISS".equals(tree.path("status").asText())) {
        return null;
      }
      JsonNode binding = tree.get("catalogBinding");
      if (binding != null && binding.isObject()) {
        String version = firstNonBlank(text(binding, "version"), text(tree, "version"));
        if (version.isBlank() || (!pinnedVersion.isBlank() && !pinnedVersion.equals(version))) {
          return null;
        }
        return new ApiHubHit(
            hint,
            text(binding, "systemId"),
            version,
            text(binding, "integrationOperationId"),
            text(binding, "protocol"),
            text(binding, "method"),
            text(binding, "path"),
            List.of("request", "success", "failure"));
      }
      ApiHubRequirementRefs refs =
          ApiHubSearchHitParser.parseSingleClearHit(response, "rest", null);
      if (refs == null) {
        return null;
      }
      String version = refs.version() == null ? "" : refs.version();
      if (!pinnedVersion.isBlank() && !pinnedVersion.equals(version)) {
        return null;
      }
      if (version.isBlank()) {
        return null;
      }
      JsonNode operation = findOperation(tree, refs.operationId());
      return new ApiHubHit(
          hint,
          refs.packageId(),
          version,
          refs.operationId(),
          text(operation, "protocol").isBlank() ? "http" : text(operation, "protocol"),
          text(operation, "method"),
          text(operation, "path"),
          List.of("request", "success", "failure"));
    } catch (Exception failure) {
      return null;
    }
  }

  private static CatalogHit hit(CatalogMatch match, String hint, String version) {
    return new CatalogHit(
        hint,
        match.systemId(),
        match.specificationGroupId(),
        match.specificationId(),
        version,
        match.integrationOperationId(),
        match.protocol(),
        match.method(),
        match.path(),
        List.of("request", "success", "failure"));
  }

  /**
   * An exact catalog hit keeps its own version. A blank pin is not stored in its place.
   * The catalog match has no release field; the evidence reference may carry {@code version:}.
   */
  private static String catalogVersion(CatalogMatch match, String release) {
    if (release != null && !release.isBlank()) {
      return release;
    }
    String evidence = match.evidenceRef() == null ? "" : match.evidenceRef();
    int marker = evidence.indexOf("version:");
    if (marker >= 0) {
      String version = evidence.substring(marker + "version:".length()).trim();
      if (!version.isBlank()) {
        return version;
      }
    }
    return "catalog";
  }

  private static JsonNode findOperation(JsonNode tree, String operationId) {
    for (String field : List.of("operations", "items")) {
      JsonNode list = tree.get(field);
      if (list == null || !list.isArray()) {
        continue;
      }
      for (JsonNode item : list) {
        if (operationId.equals(item.path("operationId").asText())) {
          return item;
        }
      }
      if (list.size() == 1) {
        return list.get(0);
      }
    }
    return tree;
  }

  private static String firstNonBlank(String left, String right) {
    if (left != null && !left.isBlank()) {
      return left;
    }
    return right == null ? "" : right;
  }

  private static String text(JsonNode tree, String field) {
    if (tree == null) {
      return "";
    }
    JsonNode value = tree.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    return value.asText("");
  }
}
