package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;

@ExtendWith(MockitoExtension.class)
class CatalogChainPublicationServiceTest {

  private static final String PIPELINE_ID = "pipeline-1";
  private static final String ATTEMPT_LABEL =
      CatalogChainPublicationService.ATTEMPT_LABEL_PREFIX + PIPELINE_ID;

  @Mock private CatalogRestClient catalogRestClient;

  private CatalogChainPublicationService service;

  @BeforeEach
  void setUp() {
    service = new CatalogChainPublicationService(catalogRestClient);
  }

  @Test
  void reusesExistingChainWhenExactlyOneMatch() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "chain-1",
                    "demo-chain",
                    "Demo",
                    "CHAIN",
                    List.of(new CatalogChainLabel(ATTEMPT_LABEL, true)))));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-1", chainId);
    verify(catalogRestClient, never()).createChain(any());
  }

  @Test
  void createsChainWhenSearchReturnsNoMatches() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(List.of());
    when(catalogRestClient.createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL)))
        .thenReturn(new CatalogRestClient.ChainDto("chain-new", "demo-chain", "Demo"));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-new", chainId);
    verify(catalogRestClient)
        .createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL));
  }

  @Test
  void throwsAmbiguousWhenMultipleMatchesOnSearch() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(
            List.of(
                chainItem("chain-a"),
                chainItem("chain-b")));

    AmbiguousPublicationAttemptException error =
        assertThrows(
            AmbiguousPublicationAttemptException.class,
            () -> service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo"));

    assertEquals(ATTEMPT_LABEL, error.attemptLabel());
    assertEquals(List.of("chain-a", "chain-b"), error.matchingChainIds());
    verify(catalogRestClient, never()).createChain(any());
  }

  @Test
  void recoversAfterCreateFailureWhenReadBackFindsOneChain() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "chain-recovered",
                    "demo-chain",
                    "Demo",
                    "CHAIN",
                    List.of(new CatalogChainLabel(ATTEMPT_LABEL, true)))));
    when(catalogRestClient.createChain(any(CatalogCreateChainRequest.class)))
        .thenThrow(new RuntimeException("timeout"));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-recovered", chainId);
    verify(catalogRestClient).createChain(any(CatalogCreateChainRequest.class));
    verify(catalogRestClient, org.mockito.Mockito.times(2))
        .searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL));
  }

  @Test
  void throwsAmbiguousWhenReadBackFindsMultipleChains() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(List.of())
        .thenReturn(List.of(chainItem("chain-a"), chainItem("chain-b")));
    when(catalogRestClient.createChain(any(CatalogCreateChainRequest.class)))
        .thenThrow(new RuntimeException("timeout"));

    AmbiguousPublicationAttemptException error =
        assertThrows(
            AmbiguousPublicationAttemptException.class,
            () -> service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo"));

    assertEquals(ATTEMPT_LABEL, error.attemptLabel());
    assertEquals(List.of("chain-a", "chain-b"), error.matchingChainIds());
  }

  @Test
  void rethrowsCreateFailureWhenReadBackFindsNoChains() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(List.of());
    RuntimeException createFailure = new RuntimeException("catalog down");
    when(catalogRestClient.createChain(any(CatalogCreateChainRequest.class)))
        .thenThrow(createFailure);

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo"));

    assertEquals("catalog down", error.getMessage());
    verify(catalogRestClient, org.mockito.Mockito.times(2))
        .searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL));
  }

  @Test
  void substringOnlyMatchDoesNotAuthorizeReuse() {
    String partial = ATTEMPT_LABEL.substring(0, ATTEMPT_LABEL.length() - 4);
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "chain-partial",
                    "demo-chain",
                    "Demo",
                    "CHAIN",
                    List.of(new CatalogChainLabel(partial, true)))));
    when(catalogRestClient.createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL)))
        .thenReturn(new CatalogRestClient.ChainDto("chain-new", "demo-chain", "Demo"));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-new", chainId);
    verify(catalogRestClient)
        .createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL));
  }

  @Test
  void folderResultDoesNotAuthorizeReuse() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "folder-1",
                    "demo-folder",
                    null,
                    "FOLDER",
                    List.of(new CatalogChainLabel(ATTEMPT_LABEL, true)))));
    when(catalogRestClient.createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL)))
        .thenReturn(new CatalogRestClient.ChainDto("chain-new", "demo-chain", "Demo"));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-new", chainId);
    verify(catalogRestClient)
        .createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL));
  }

  @Test
  void nonTechnicalLabelDoesNotAuthorizeReuse() {
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(ATTEMPT_LABEL)))
        .thenReturn(
            List.of(
                new CatalogRestClient.FolderItemDto(
                    "chain-1",
                    "demo-chain",
                    "Demo",
                    "CHAIN",
                    List.of(new CatalogChainLabel(ATTEMPT_LABEL, false)))));
    when(catalogRestClient.createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL)))
        .thenReturn(new CatalogRestClient.ChainDto("chain-new", "demo-chain", "Demo"));

    String chainId = service.resolveOrCreate(PIPELINE_ID, "demo-chain", "Demo");

    assertEquals("chain-new", chainId);
    verify(catalogRestClient)
        .createChain(
            CatalogCreateChainRequest.forPublicationAttempt("demo-chain", "Demo", ATTEMPT_LABEL));
  }

  @Test
  void requiresPipelineIdAndChainName() {
    assertThrows(IllegalArgumentException.class, () -> service.resolveOrCreate("  ", "demo", null));
    assertThrows(IllegalArgumentException.class, () -> service.resolveOrCreate(PIPELINE_ID, "  ", null));
  }

  private static CatalogRestClient.FolderItemDto chainItem(String chainId) {
    return new CatalogRestClient.FolderItemDto(
        chainId,
        "demo-chain",
        "Demo",
        "CHAIN",
        List.of(new CatalogChainLabel(ATTEMPT_LABEL, true)));
  }
}
