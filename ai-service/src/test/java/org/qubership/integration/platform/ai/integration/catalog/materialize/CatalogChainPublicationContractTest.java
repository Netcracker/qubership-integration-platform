package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;

@ExtendWith(MockitoExtension.class)
class CatalogChainPublicationContractTest {

  private static final String PIPELINE_ID = "pipeline-contract";
  private static final String ATTEMPT_LABEL =
      CatalogChainPublicationService.ATTEMPT_LABEL_PREFIX + PIPELINE_ID;


  @Mock private CatalogRestClient catalogRestClient;

  private CatalogChainPublicationService service;

  @BeforeEach
  void setUp() {
    service = new CatalogChainPublicationService(catalogRestClient);
  }

  @Test
  void searchPostsExpectedJson() {
    when(catalogRestClient.searchFolderItems(any(CatalogChainSearchRequest.class)))
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "chain-1",
                    "demo-chain",
                    "Demo",
                    "CHAIN",
                    List.of(
                        new org.qubership.integration.platform.ai.integration.catalog.model
                            .CatalogChainLabel(ATTEMPT_LABEL, true)))));

    service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    ArgumentCaptor<CatalogChainSearchRequest> captor =
        ArgumentCaptor.forClass(CatalogChainSearchRequest.class);
    verify(catalogRestClient).searchFolderItems(captor.capture());
    assertEquals(ATTEMPT_LABEL, captor.getValue().searchCondition());
  }

  @Test
  void createChainPostsPublicationAttemptLabel() {
    when(catalogRestClient.searchFolderItems(any(CatalogChainSearchRequest.class)))
        .thenReturn(List.of());
    when(catalogRestClient.createChain(any(CatalogCreateChainRequest.class)))
        .thenReturn(new CatalogRestClient.ChainDto("chain-new", "demo-chain", "Demo"));

    service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    ArgumentCaptor<CatalogCreateChainRequest> captor =
        ArgumentCaptor.forClass(CatalogCreateChainRequest.class);
    verify(catalogRestClient).createChain(captor.capture());
    CatalogCreateChainRequest request = captor.getValue();
    assertEquals("demo-chain", request.name());
    assertEquals("Demo", request.description());
    assertEquals(1, request.labels().size());
    assertEquals(ATTEMPT_LABEL, request.labels().get(0).name());
    assertTrue(request.labels().get(0).technical());
  }
}
