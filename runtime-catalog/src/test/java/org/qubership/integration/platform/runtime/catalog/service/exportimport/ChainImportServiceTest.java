package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.FolderService;
import org.qubership.integration.platform.runtime.catalog.service.MaskedFieldsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainImportServiceTest {

    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private FolderService folderService;
    @Mock
    private ChainExternalEntityMapper chainExternalEntityMapper;
    @Mock
    private ChainService chainService;
    @Mock
    private DependencyService dependencyService;
    @Mock
    private ElementService elementService;
    @Mock
    private MaskedFieldsService maskedFieldsService;
    @Mock
    private ActionsLogService actionsLogService;

    @InjectMocks
    private ChainImportService service;

    private static ChainExternalEntity externalChain(String group) {
        return ChainExternalEntity.builder()
                .id("c1")
                .name("Chain 1")
                .metaInfo(group == null ? null : MetaInfoExternalEntity.builder().group(group).build())
                .content(ChainExternalContentEntity.builder().build())
                .build();
    }

    @DisplayName("resolveRootFolderName should return the first group segment")
    @Test
    void shouldResolveFirstSegmentAsRootName() {
        assertEquals("A", ChainImportService.resolveRootFolderName(externalChain("A/B/C")));
    }

    @DisplayName("resolveRootFolderName should be null when metaInfo is absent")
    @Test
    void shouldResolveNullRootNameWhenNoMetaInfo() {
        assertNull(ChainImportService.resolveRootFolderName(externalChain(null)));
    }

    @DisplayName("resolveRootFolderName should be null when the group is blank")
    @Test
    void shouldResolveNullRootNameWhenGroupBlank() {
        assertNull(ChainImportService.resolveRootFolderName(externalChain("   ")));
    }

    @DisplayName("resolveOrCreateRootFolder should reuse an existing root folder")
    @Test
    void shouldReuseExistingRootFolder() {
        Folder existing = Folder.builder().name("A").build();
        when(folderService.findFirstByName("A", null)).thenReturn(existing);

        Folder result = service.resolveOrCreateRootFolder(externalChain("A/B"));

        assertSame(existing, result);
        verify(folderService, never()).save(any(Folder.class), nullable(String.class));
    }

    @DisplayName("resolveOrCreateRootFolder should create the root folder when it is missing")
    @Test
    void shouldCreateMissingRootFolder() {
        Folder saved = Folder.builder().name("A").build();
        when(folderService.findFirstByName("A", null)).thenReturn(null);
        when(folderService.save(any(Folder.class), nullable(String.class))).thenReturn(saved);

        Folder result = service.resolveOrCreateRootFolder(externalChain("A"));

        assertSame(saved, result);
    }

    @DisplayName("resolveOrCreateRootFolder should return null and touch nothing when there is no group")
    @Test
    void shouldReturnNullRootFolderWhenNoGroup() {
        assertNull(service.resolveOrCreateRootFolder(externalChain(null)));
        verifyNoInteractions(folderService);
    }

    @DisplayName("setActualChainState should persist the folder tree before the chain")
    @Test
    void shouldPersistFolderBeforeChain() {
        Folder folder = Folder.builder().name("A").build();
        Chain imported = Chain.builder().id("c1").name("n").build();
        imported.setParentFolder(folder);
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.empty());
        when(folderService.setActualizedFolderState(folder)).thenReturn(folder);

        service.saveImportedChainBackward(imported);

        InOrder order = inOrder(folderService, chainService);
        order.verify(folderService).setActualizedFolderState(folder);
        order.verify(chainService).setActualizedChainState(null, imported);
    }

    @DisplayName("setActualChainState should skip folder persistence when the chain has no parent")
    @Test
    void shouldSkipFolderPersistWhenNoParent() {
        Chain imported = Chain.builder().id("c1").name("n").build();
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.empty());

        service.saveImportedChainBackward(imported);

        verify(folderService, never()).setActualizedFolderState(any());
        verify(chainService).setActualizedChainState(null, imported);
    }

    @DisplayName("saveImportedChain should reuse an existing root folder and report UPDATED for an existing chain")
    @Test
    void shouldImportIntoExistingFolderAsUpdate() {
        Chain existingChain = Chain.builder().id("c1").name("Chain 1").build();
        Folder existingFolder = Folder.builder().name("A").build();
        Chain mapped = Chain.builder().id("c1").name("Chain 1").build();
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.of(existingChain));
        when(folderService.findFirstByName("A", null)).thenReturn(existingFolder);
        ArgumentCaptor<ChainExternalMapperEntity> captor =
                ArgumentCaptor.forClass(ChainExternalMapperEntity.class);
        when(chainExternalEntityMapper.toInternalEntity(captor.capture())).thenReturn(mapped);

        ImportChainResult result =
                service.saveImportedChain(externalChain("A/B"), null, Collections.emptySet());

        assertEquals(ImportEntityStatus.UPDATED, result.getStatus());
        assertSame(existingFolder, captor.getValue().getExistingFolder());
        verify(folderService, never()).save(any(Folder.class), nullable(String.class));
    }
}
