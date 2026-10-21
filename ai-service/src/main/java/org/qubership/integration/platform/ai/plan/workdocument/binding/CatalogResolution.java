package org.qubership.integration.platform.ai.plan.workdocument.binding;

import java.util.List;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;

/**
 * Catalog and APIHub reads for one selected operation. {@link ResolveApiOperationSeam} is the
 * production implementation. Tests supply a fake.
 */
public interface CatalogResolution {

  CatalogLookup lookup(String operationHint, String pinnedVersion);

  /**
   * Same lookup with the step wording that names the service. Callers that only know the operation
   * keep the two-argument method.
   */
  default CatalogLookup lookup(String operationHint, String pinnedVersion, String systemHint) {
    return lookup(operationHint, pinnedVersion);
  }

  /**
   * {@code interactionId} is the logical step id. The operation hint is not an interaction id.
   */
  ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion);

  void importContract(ApiHubHit hit);

  /**
   * Loads schemas for a pinned catalog operation. Adapters that only search or list operations
   * return {@link ContractMaterial.Unavailable} and do not invent a schema.
   */
  default ContractMaterial loadContract(ResolvedWorkBinding binding) {
    String reference = "";
    String operationId = "";
    String version = "";
    if (binding != null) {
      operationId = binding.operationId() == null ? "" : binding.operationId();
      version = binding.version() == null ? "" : binding.version();
      if (binding.contractReferences() != null && !binding.contractReferences().isEmpty()) {
        reference = binding.contractReferences().get(0);
      }
    }
    return new ContractMaterial.Unavailable(
        reference, operationId, version, "This catalog adapter cannot load schemas.");
  }
}

/** Result of a runtime-catalog lookup. A miss is not permission to change a pinned version. */
sealed interface CatalogLookup {

  record Hit(CatalogHit hit) implements CatalogLookup {}

  record Miss() implements CatalogLookup {}

  record Ambiguous(List<String> candidateIds) implements CatalogLookup {}

  record PinnedUnavailable(String version) implements CatalogLookup {}

  /** An exact catalog operation had no version to store. */
  record VersionAbsent() implements CatalogLookup {}
}

record CatalogHit(
    String hint,
    String catalogId,
    String specificationGroupId,
    String specificationId,
    String version,
    String operationId,
    String protocol,
    String method,
    String path,
    List<String> exposedPorts) {}

record ApiHubHit(
    String hint,
    String packageId,
    String version,
    String operationId,
    String protocol,
    String method,
    String path,
    List<String> exposedPorts) {}
