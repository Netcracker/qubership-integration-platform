package org.qubership.integration.platform.runtime.catalog.service.helpers;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.FolderContentFilter;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainFinderServiceTest {

    @Mock
    private ChainRepository chainRepository;

    @InjectMocks
    private ChainFinderService chainFinderService;

    @Test
    void shouldFindAllChains() {
        List<Chain> chains = List.of(new Chain(), new Chain());
        when(chainRepository.findAll()).thenReturn(chains);

        List<Chain> result = chainFinderService.findAll();

        assertThat(result).isEqualTo(chains);
    }

    @Test
    void shouldFindAllByIds() {
        List<String> ids = List.of("id1", "id2");
        List<Chain> chains = List.of(new Chain(), new Chain());
        when(chainRepository.findAllById(ids)).thenReturn(chains);

        List<Chain> result = chainFinderService.findAllById(ids);

        assertThat(result).isEqualTo(chains);
    }

    @Test
    void shouldFindById() {
        String chainId = "chainId";
        Chain chain = new Chain();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        Chain result = chainFinderService.findById(chainId);

        assertThat(result).isEqualTo(chain);
    }

    @Test
    void shouldThrowExceptionWhenChainNotFound() {
        String chainId = "missingId";
        when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainFinderService.findById(chainId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(chainId);
    }

    @Test
    void shouldFindChainsInFolderWithFilter() {
        String folderId = "folderId";
        FolderContentFilter filter = mock(FolderContentFilter.class);
        List<Chain> chains = List.of(new Chain());
        when(chainRepository.findAll(any(Specification.class))).thenReturn(chains);

        List<Chain> result = chainFinderService.findChainsInFolder(folderId, filter);

        assertThat(result).isEqualTo(chains);
        verify(chainRepository).findAll(any(Specification.class));
    }

    @Test
    void shouldFindChainsInRootWithFilter() {
        FolderContentFilter filter = mock(FolderContentFilter.class);
        List<Chain> chains = List.of(new Chain());
        when(chainRepository.findAll(any(Specification.class))).thenReturn(chains);

        List<Chain> result = chainFinderService.findInRoot(filter);

        assertThat(result).isEqualTo(chains);
        verify(chainRepository).findAll(any(Specification.class));
    }

    @Test
    void shouldFindAllChainsToRootParentFolder() {
        String openedFolderId = "folderTest";
        List<Chain> chains = List.of(new Chain(), new Chain());
        when(chainRepository.findAllChainsToRootParentFolder(openedFolderId)).thenReturn(chains);

        List<Chain> result = chainFinderService.findAllChainsToRootParentFolder(openedFolderId);

        assertThat(result).isEqualTo(chains);
    }

    @Test
    void shouldTryFindById() {
        String chainId = "chainTest";
        Chain chain = new Chain();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        Optional<Chain> result = chainFinderService.tryFindById(chainId);

        assertThat(result).isPresent().contains(chain);
    }

    @Test
    void shouldReturnEmptyOptionalWhenChainNotFound() {
        String chainId = "chainTest";
        when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        Optional<Chain> result = chainFinderService.tryFindById(chainId);

        assertThat(result).isEmpty();
    }
}

