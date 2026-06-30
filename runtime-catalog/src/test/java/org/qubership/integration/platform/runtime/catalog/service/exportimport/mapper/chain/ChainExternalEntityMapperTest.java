package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementsExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainExternalEntityMapperTest {

    private ChainElementsExternalEntityMapper chainElementsMapper;
    private ChainExternalEntityMapper mapper;

    @BeforeEach
    void setUp() {
        chainElementsMapper = mock(ChainElementsExternalEntityMapper.class);
        when(chainElementsMapper.toExternalEntity(any()))
                .thenReturn(ChainElementsExternalMapperEntity.builder().build());
        when(chainElementsMapper.toInternalEntity(any())).thenReturn(List.of());
        mapper = new ChainExternalEntityMapper(
                chainElementsMapper,
                List.of(),
                URI.create("http://qubership.org/schemas/product/qip/chain"));
    }

    // ---- export: folder hierarchy -> metaInfo.group ----

    @DisplayName("Export should write the folder path into metaInfo.group")
    @Test
    void shouldExportFolderPathAsGroup() {
        Folder a = Folder.builder().name("A").build();
        Folder b = Folder.builder().name("B").build();
        a.addChildFolder(b);
        Chain chain = Chain.builder().id("c1").name("Chain 1").build();
        chain.setParentFolder(b);

        ChainExternalMapperEntity result = mapper.toExternalEntity(chain);

        assertEquals("A/B", result.getChainExternalEntity().getMetaInfo().getGroup());
    }

    @DisplayName("Export should sanitize forbidden characters in folder names")
    @Test
    void shouldSanitizeGroupOnExport() {
        Folder folder = Folder.builder().name("a:b").build();
        Chain chain = Chain.builder().id("c1").name("Chain 1").build();
        chain.setParentFolder(folder);

        ChainExternalMapperEntity result = mapper.toExternalEntity(chain);

        assertEquals("a-b", result.getChainExternalEntity().getMetaInfo().getGroup());
    }

    @DisplayName("Export should leave metaInfo null for a chain at the root")
    @Test
    void shouldExportNullMetaInfoForRootChain() {
        Chain chain = Chain.builder().id("c1").name("Chain 1").build();

        ChainExternalMapperEntity result = mapper.toExternalEntity(chain);

        assertNull(result.getChainExternalEntity().getMetaInfo());
    }

    // ---- import: metaInfo.group -> folder hierarchy ----

    @DisplayName("Import should build a folder hierarchy from metaInfo.group")
    @Test
    void shouldImportGroupAsFolderHierarchy() {
        Chain result = mapper.toInternalEntity(importEntity("A/B/C", null));

        Folder leaf = result.getParentFolder();
        assertEquals("C", leaf.getName());
        assertEquals("B", leaf.getParentFolder().getName());
        assertEquals("A", leaf.getParentFolder().getParentFolder().getName());
        assertNull(leaf.getParentFolder().getParentFolder().getParentFolder());
    }

    @DisplayName("Import should leave the chain at the root when the group is empty")
    @Test
    void shouldImportEmptyGroupAsRoot() {
        Chain result = mapper.toInternalEntity(importEntity("", null));

        assertNull(result.getParentFolder());
    }

    @DisplayName("Import should reuse an existing folder subtree instead of creating duplicates")
    @Test
    void shouldReuseExistingFolders() {
        Folder existingA = Folder.builder().name("A").build();
        Folder existingB = Folder.builder().name("B").build();
        existingA.addChildFolder(existingB);

        Chain result = mapper.toInternalEntity(importEntity("A/B", existingA));

        assertSame(existingB, result.getParentFolder());
        assertSame(existingA, result.getParentFolder().getParentFolder());
    }

    private ChainExternalMapperEntity importEntity(String group, Folder existingFolder) {
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("c1")
                .name("Chain 1")
                .metaInfo(MetaInfoExternalEntity.builder().group(group).build())
                .content(ChainExternalContentEntity.builder().build())
                .build();
        return ChainExternalMapperEntity.builder()
                .chainExternalEntity(externalEntity)
                .existingChain(Chain.builder().build())
                .existingFolder(existingFolder)
                .build();
    }
}
