package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;

/** Idempotent catalog chain creation keyed by durable pipeline publication-attempt labels. */
@ApplicationScoped
public class CatalogChainPublicationService {

  public static final String ATTEMPT_LABEL_PREFIX = "qip-ai-publication-attempt:";

  private static final Logger LOG = Logger.getLogger(CatalogChainPublicationService.class);
  private static final String CHAIN_ITEM_TYPE = "CHAIN";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogChainPublicationService(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  public String resolveOrCreate(String pipelineId, String chainName, String chainDescription) {
    if (pipelineId == null || pipelineId.isBlank()) {
      throw new IllegalArgumentException("pipelineId is required");
    }
    if (chainName == null || chainName.isBlank()) {
      throw new IllegalArgumentException("chainName is required");
    }

    String attemptLabel = attemptLabel(pipelineId);
    LOG.infof(
        "Catalog publication attempt: pipelineId=%s attemptLabel=%s chainName=%s",
        pipelineId, attemptLabel, chainName);

    List<String> existing = findExactChainIds(attemptLabel);
    if (existing.size() == 1) {
      String chainId = existing.get(0);
      LOG.infof(
          "Catalog chain reused for attempt: pipelineId=%s attemptLabel=%s chainId=%s",
          pipelineId, attemptLabel, chainId);
      return chainId;
    }
    if (existing.size() > 1) {
      throw new AmbiguousPublicationAttemptException(attemptLabel, existing);
    }

    try {
      CatalogRestClient.ChainDto created =
          catalogRestClient.createChain(
              CatalogCreateChainRequest.forPublicationAttempt(
                  chainName, chainDescription, attemptLabel));
      String chainId = requireChainId(created);
      LOG.infof(
          "Catalog chain created for attempt: pipelineId=%s attemptLabel=%s chainId=%s",
          pipelineId, attemptLabel, chainId);
      return chainId;
    } catch (RuntimeException createFailure) {
      List<String> readBack = findExactChainIds(attemptLabel);
      if (readBack.size() == 1) {
        String chainId = readBack.get(0);
        LOG.infof(
            "Catalog chain recovered after ambiguous create: pipelineId=%s attemptLabel=%s"
                + " chainId=%s",
            pipelineId, attemptLabel, chainId);
        return chainId;
      }
      if (readBack.size() > 1) {
        throw new AmbiguousPublicationAttemptException(attemptLabel, readBack, createFailure);
      }
      throw createFailure;
    }
  }

  private static String attemptLabel(String pipelineId) {
    return ATTEMPT_LABEL_PREFIX + pipelineId;
  }

  private List<String> findExactChainIds(String attemptLabel) {
    List<CatalogRestClient.FolderItemDto> results =
        catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(attemptLabel));
    if (results == null || results.isEmpty()) {
      return List.of();
    }

    List<String> matches = new ArrayList<>();
    for (CatalogRestClient.FolderItemDto item : results) {
      if (item == null || item.id() == null || item.id().isBlank()) {
        continue;
      }
      if (!CHAIN_ITEM_TYPE.equals(item.itemType())) {
        continue;
      }
      if (!hasExactTechnicalLabel(item.labels(), attemptLabel)) {
        continue;
      }
      matches.add(item.id());
    }
    matches.sort(Comparator.naturalOrder());
    return List.copyOf(matches);
  }

  private static boolean hasExactTechnicalLabel(
      List<CatalogChainLabel> labels, String attemptLabel) {
    if (labels == null || labels.isEmpty()) {
      return false;
    }
    for (CatalogChainLabel label : labels) {
      if (label != null
          && label.technical()
          && Objects.equals(attemptLabel, label.name())) {
        return true;
      }
    }
    return false;
  }

  private static String requireChainId(CatalogRestClient.ChainDto chain) {
    if (chain == null || chain.id() == null || chain.id().isBlank()) {
      throw new IllegalStateException("createChain did not return a chain id");
    }
    return chain.id();
  }
}
