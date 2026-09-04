package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.Optional;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

/** Result of importing an API Hub specification into runtime-catalog. */
public record ApiHubSpecificationImportResult(
    String systemId,
    String specificationId,
    String specificationGroupId,
    String importId,
    String specificationGroupName,
    Optional<String> catalogOperationId,
    String systemType) {

  public ApiHubSpecificationImportResult {
    if (systemType == null || systemType.isBlank()) {
      systemType = ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE;
    }
  }

  /** Compatibility constructor for callers that do not track catalog system type yet. */
  public ApiHubSpecificationImportResult(
      String systemId,
      String specificationId,
      String specificationGroupId,
      String importId,
      String specificationGroupName,
      Optional<String> catalogOperationId) {
    this(
        systemId,
        specificationId,
        specificationGroupId,
        importId,
        specificationGroupName,
        catalogOperationId,
        ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE);
  }
}
