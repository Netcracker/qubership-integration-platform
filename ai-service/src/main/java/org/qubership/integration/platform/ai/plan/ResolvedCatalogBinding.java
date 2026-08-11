package org.qubership.integration.platform.ai.plan;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/** Catalog service binding resolved from gather tools or ApiHub specification import. */
public record ResolvedCatalogBinding(
    String systemId,
    String specificationId,
    String specificationGroupId,
    String integrationOperationId,
    String systemType) {

  private static final Set<String> ALLOWED_SYSTEM_TYPES =
      Set.of("INTERNAL", "EXTERNAL", "IMPLEMENTED");

  public ResolvedCatalogBinding(
      String systemId,
      String specificationId,
      String specificationGroupId,
      String integrationOperationId) {
    this(systemId, specificationId, specificationGroupId, integrationOperationId, null);
  }

  public static ResolvedCatalogBinding fromImportResult(ApiHubSpecificationImportResult result) {
    return new ResolvedCatalogBinding(
        result.systemId(),
        result.specificationId(),
        result.specificationGroupId(),
        result.catalogOperationId().orElse(null),
        ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE);
  }

  public Optional<String> optionalOperationId() {
    return Optional.ofNullable(integrationOperationId).filter(id -> !id.isBlank());
  }

  /**
   * Overwrites {@code systemType} from the catalog cache when the system was returned by
   * {@code searchCatalogSystems} in this conversation.
   */
  public static ResolvedCatalogBinding enrichFromCache(
      ConversationCatalogCache cache, String conversationId, ResolvedCatalogBinding binding) {
    if (binding == null) {
      return null;
    }
    if (cache == null) {
      return binding;
    }
    String systemId = CatalogStrings.blankToNull(binding.systemId());
    if (systemId == null) {
      return binding;
    }
    String resolvedType =
        cache
            .findSystem(conversationId, systemId)
            .map(CatalogRestClient.SystemDto::type)
            .map(ResolvedCatalogBinding::normalizeSystemType)
            .orElse(normalizeSystemType(binding.systemType()));
    return new ResolvedCatalogBinding(
        binding.systemId(),
        binding.specificationId(),
        binding.specificationGroupId(),
        binding.integrationOperationId(),
        resolvedType);
  }

  public static boolean isAllowedSystemType(String systemType) {
    String normalized = normalizeSystemType(systemType);
    return normalized != null && ALLOWED_SYSTEM_TYPES.contains(normalized);
  }

  static String normalizeSystemType(String systemType) {
    String trimmed = CatalogStrings.blankToNull(systemType);
    return trimmed == null ? null : trimmed.trim().toUpperCase(Locale.ROOT);
  }
}
