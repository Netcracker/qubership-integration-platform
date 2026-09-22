package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.events.ChainsDeletedEvent;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.FolderRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderServiceDeletionTest {
    @Mock
    FolderRepository folderRepository;
    @Mock
    ChainRepository chainRepository;
    @Mock
    DeploymentService deploymentService;
    @Mock
    ActionsLogService actionsLogService;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;
    @InjectMocks
    FolderService folderService;

    @Test
    void deletingFolderPublishesIdsOfNestedChains() {
        Folder root = Folder.builder().id("root").name("root").build();
        Folder sub = Folder.builder().id("sub").name("sub").parentFolder(root).build();
        Chain top = Chain.builder().id("top").name("top").parentFolder(root).build();
        Chain nested = Chain.builder().id("nested").name("nested").parentFolder(sub).build();
        root.setFolderList(List.of(sub));
        root.setChainList(List.of(top));
        sub.setChainList(List.of(nested));
        when(folderRepository.findById("root")).thenReturn(Optional.of(root));

        folderService.deleteById("root");

        verify(applicationEventPublisher).publishEvent(new ChainsDeletedEvent(List.of("top", "nested")));
    }

    @Test
    void bulkDeletingFoldersPublishesIdsOfTheirChains() {
        Folder folder = Folder.builder().id("f").name("f").build();
        Chain first = Chain.builder().id("first").name("first").parentFolder(folder).build();
        Chain second = Chain.builder().id("second").name("second").parentFolder(folder).build();
        when(chainRepository.findAllChainsInFolders(List.of("f"))).thenReturn(List.of(first, second));

        folderService.deleteByIds(List.of("f"));

        verify(applicationEventPublisher).publishEvent(new ChainsDeletedEvent(List.of("first", "second")));
    }
}
