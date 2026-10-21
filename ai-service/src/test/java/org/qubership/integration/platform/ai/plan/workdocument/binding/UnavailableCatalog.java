package org.qubership.integration.platform.ai.plan.workdocument.binding;

/** Live harness guard. A missing catalog client fails the checkpoint instead of inventing a hit. */
public final class UnavailableCatalog implements CatalogResolution {

  @Override
  public CatalogLookup lookup(String operationHint, String pinnedVersion) {
    throw unavailable();
  }

  @Override
  public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
    throw unavailable();
  }

  @Override
  public void importContract(ApiHubHit hit) {
    throw unavailable();
  }

  @Override
  public ContractMaterial loadContract(org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding binding) {
    throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "CATALOG_CLIENT_UNAVAILABLE: this process has no catalog client. The harness does not invent a binding.");
  }
}
