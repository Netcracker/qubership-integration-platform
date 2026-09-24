package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool;

/**
 * Runtime-catalog lookup stays on {@link CatalogOperationLookup}. APIHub search goes through
 * {@link CatalogFirstApiHubDiscoveryTool#resolveApiOperation}. This seam does not import a
 * contract.
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
      return new CatalogLookup.Hit(hit(exact.match(), operationHint, release));
    }
    if (!release.isBlank()) {
      return new CatalogLookup.PinnedUnavailable(release);
    }
    return new CatalogLookup.Miss();
  }

  @Override
  public ApiHubHit searchApiHub(String operationHint, String pinnedVersion) {
    String release = pinnedVersion == null ? "" : pinnedVersion;
    String response =
        discovery.resolveApiOperation(operationHint, "", "", null, "", release);
    return parse(response, operationHint, release);
  }

  @Override
  public void importContract(ApiHubHit hit) {
    // Chain creation imports the pinned contract. Binding keeps the APIHub reference only.
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

  private ApiHubHit parse(String response, String hint, String pinnedVersion) {
    if (response == null || response.isBlank()) {
      return null;
    }
    try {
      JsonNode tree = json.readTree(response);
      String version = text(tree, "version");
      if (version.isBlank()) {
        version = pinnedVersion;
      }
      String packageId = text(tree, "packageId");
      String operationId = text(tree, "operationId");
      if (packageId.isBlank() || operationId.isBlank() || version.isBlank()) {
        return null;
      }
      return new ApiHubHit(
          hint,
          packageId,
          version,
          operationId,
          text(tree, "protocol"),
          text(tree, "method"),
          text(tree, "path"),
          List.of("request", "success", "failure"));
    } catch (Exception failure) {
      return null;
    }
  }

  private static String text(JsonNode tree, String field) {
    JsonNode value = tree.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    return value.asText("");
  }
}
