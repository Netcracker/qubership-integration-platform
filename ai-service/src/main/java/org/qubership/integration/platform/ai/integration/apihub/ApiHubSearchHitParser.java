package org.qubership.integration.platform.ai.integration.apihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Parses API Hub {@code search_api_operations} tool text into a single importable candidate when
 * the hit list is unambiguous.
 */
public final class ApiHubSearchHitParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ApiHubSearchHitParser() {}

  /** Distinct operation IDs from a search tool payload (empty when none parse). */
  public static List<String> collectOperationIds(String toolResult) {
    List<ApiHubRequirementRefs> hits = parseHits(toolResult, ApiHubRequirementRefs.DEFAULT_API_TYPE, null);
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (ApiHubRequirementRefs hit : hits) {
      String operationId = CatalogStrings.blankToNull(hit.operationId());
      if (operationId != null) {
        ids.add(operationId);
      }
    }
    return List.copyOf(ids);
  }

  /**
   * Returns an importable candidate when exactly one distinct package/version/operation (or
   * document) hit is present; otherwise null.
   */
  public static ApiHubRequirementRefs parseSingleClearHit(
      String toolResult, String apiType, String preferredPackageId) {
    List<ApiHubRequirementRefs> hits = parseHits(toolResult, apiType, preferredPackageId);
    if (hits.isEmpty()) {
      return null;
    }
    if (hits.size() != 1) {
      // Same package/version/op repeated is still a clear single candidate.
      ApiHubRequirementRefs first = hits.get(0);
      boolean allSame =
          hits.stream()
              .allMatch(
                  hit ->
                      equalsNullable(first.packageId(), hit.packageId())
                          && equalsNullable(first.version(), hit.version())
                          && equalsNullable(first.operationId(), hit.operationId())
                          && equalsNullable(first.documentId(), hit.documentId()));
      if (!allSame) {
        return null;
      }
      return first.hasImportableRefs() ? first : null;
    }
    ApiHubRequirementRefs only = hits.get(0);
    return only.hasImportableRefs() ? only : null;
  }

  /**
   * Best-effort importable candidate from a search payload: single clear hit, else primary
   * GET-by-id within one package, else package-level document slug when every hit shares the same
   * package/version. Used when the agent searches without {@code group} and gets many ops from one
   * package (e.g. query {@code Party Management}).
   */
  public static ApiHubRequirementRefs parseImportCandidate(
      String toolResult, String apiType, String preferredPackageId) {
    ApiHubRequirementRefs clear = parseSingleClearHit(toolResult, apiType, preferredPackageId);
    if (clear != null) {
      return clear;
    }
    ApiHubRequirementRefs byId = parsePreferredGetByIdHit(toolResult, apiType, preferredPackageId);
    if (byId != null) {
      return byId;
    }
    return parseSinglePackageDocumentFallback(toolResult, apiType, preferredPackageId);
  }

  /**
   * When a package-scoped search returns multiple operations, prefer a primary GET-by-id style hit
   * (title contains "by ID" / "by Id", method path ends at the resource id) for cache backfill.
   * If {@code preferredPackageId} is null but every hit shares one packageId, that package is used.
   */
  public static ApiHubRequirementRefs parsePreferredGetByIdHit(
      String toolResult, String apiType, String preferredPackageId) {
    String preferred = CatalogStrings.blankToNull(preferredPackageId);
    List<ApiHubRequirementRefs> hits = parseHits(toolResult, apiType, preferred);
    if (hits.size() < 2) {
      return null;
    }
    if (preferred == null) {
      preferred = singleSharedPackageId(hits);
      if (preferred == null) {
        return null;
      }
      hits = parseHits(toolResult, apiType, preferred);
      if (hits.size() < 2) {
        return null;
      }
    }
    List<ApiHubRequirementRefs> byId =
        hits.stream()
            .filter(ApiHubSearchHitParser::looksLikePrimaryGetById)
            .toList();
    if (byId.size() == 1) {
      return byId.get(0);
    }
    return null;
  }

  /**
   * When many operations belong to one package/version and no primary GET-by-id stands out, seed a
   * package-level candidate ({@code documentId=api}) so gather can still enter IMPORT_PENDING.
   */
  public static ApiHubRequirementRefs parseSinglePackageDocumentFallback(
      String toolResult, String apiType, String preferredPackageId) {
    List<ApiHubRequirementRefs> hits =
        parseHits(toolResult, apiType, CatalogStrings.blankToNull(preferredPackageId));
    if (hits.size() < 2) {
      return null;
    }
    ApiHubRequirementRefs first = hits.get(0);
    String packageId = CatalogStrings.blankToNull(first.packageId());
    String version = CatalogStrings.blankToNull(first.version());
    if (packageId == null || version == null) {
      return null;
    }
    boolean samePackageVersion =
        hits.stream()
            .allMatch(
                hit ->
                    packageId.equals(hit.packageId())
                        && version.equals(CatalogStrings.blankToNull(hit.version())));
    if (!samePackageVersion) {
      return null;
    }
    String documentId =
        CatalogStrings.blankToNull(first.documentId()) != null
            ? first.documentId()
            : ApiHubRequirementRefs.DEFAULT_DOCUMENT_SLUG;
    ApiHubRequirementRefs packageLevel =
        new ApiHubRequirementRefs(
            packageId,
            version,
            null,
            documentId,
            first.resolvedApiType(),
            first.packageName(),
            first.specificationName());
    return packageLevel.hasImportableRefs() ? packageLevel : null;
  }

  private static String singleSharedPackageId(List<ApiHubRequirementRefs> hits) {
    if (hits == null || hits.isEmpty()) {
      return null;
    }
    String packageId = CatalogStrings.blankToNull(hits.get(0).packageId());
    if (packageId == null) {
      return null;
    }
    boolean allSame = hits.stream().allMatch(hit -> packageId.equals(hit.packageId()));
    return allSame ? packageId : null;
  }

  private static List<ApiHubRequirementRefs> parseHits(
      String toolResult, String apiType, String preferredPackageId) {
    if (toolResult == null || toolResult.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(toolResult);
      List<ApiHubRequirementRefs> hits = new ArrayList<>();
      collectHits(root, apiType, hits);
      if (hits.isEmpty() && root.isArray()) {
        for (JsonNode item : root) {
          collectHit(item, apiType, hits);
        }
      }
      if (hits.isEmpty()) {
        return List.of();
      }
      String preferred = CatalogStrings.blankToNull(preferredPackageId);
      if (preferred != null) {
        List<ApiHubRequirementRefs> filtered =
            hits.stream().filter(hit -> preferred.equals(hit.packageId())).toList();
        if (!filtered.isEmpty()) {
          return filtered;
        }
      }
      return hits;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static boolean looksLikePrimaryGetById(ApiHubRequirementRefs hit) {
    if (hit == null || !hit.hasImportableRefs()) {
      return false;
    }
    String title = CatalogStrings.blankToNull(hit.specificationName());
    String operationId = CatalogStrings.blankToNull(hit.operationId());
    if (title != null) {
      String lower = title.toLowerCase(Locale.ROOT);
      if (lower.contains("by id") && !lower.contains("(") && lower.startsWith("retrieve")) {
        return true;
      }
    }
    return operationId != null
        && operationId.endsWith("-_id_-get")
        && !operationId.contains("-history-")
        && !operationId.contains("-attachment-")
        && !operationId.contains("-relationship-")
        && !operationId.contains("-externalReference-");
  }

  private static void collectHits(JsonNode root, String apiType, List<ApiHubRequirementRefs> hits) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return;
    }
    if (root.isArray()) {
      for (JsonNode item : root) {
        collectHit(item, apiType, hits);
      }
      return;
    }
    JsonNode operations = root.path("operations");
    if (operations.isArray()) {
      for (JsonNode item : operations) {
        collectHit(item, apiType, hits);
      }
      return;
    }
    JsonNode results = root.path("results");
    if (results.isArray()) {
      for (JsonNode item : results) {
        collectHit(item, apiType, hits);
      }
      return;
    }
    JsonNode items = root.path("items");
    if (items.isArray()) {
      for (JsonNode item : items) {
        collectHit(item, apiType, hits);
      }
      return;
    }
    collectHit(root, apiType, hits);
  }

  private static void collectHit(JsonNode item, String apiType, List<ApiHubRequirementRefs> hits) {
    if (item == null || !item.isObject()) {
      return;
    }
    String packageId = text(item, "packageId");
    String version = text(item, "version");
    String operationId = text(item, "operationId");
    String documentId = text(item, "documentId");
    String resolvedType = text(item, "apiType");
    if (resolvedType == null) {
      resolvedType = CatalogStrings.blankToNull(apiType);
    }
    String packageName = text(item, "packageName");
    String specificationName = text(item, "title");
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            packageId,
            version,
            operationId,
            documentId,
            resolvedType,
            packageName,
            specificationName);
    if (refs.hasImportableRefs()) {
      hits.add(refs);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return CatalogStrings.blankToNull(text);
  }

  private static boolean equalsNullable(String left, String right) {
    if (left == null) {
      return right == null;
    }
    return left.equals(right);
  }
}
