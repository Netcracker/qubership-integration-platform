package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.impl.ConnectionImpl;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.impl.FolderImpl;
import org.qubership.integration.platform.chain.impl.LabelImpl;
import org.qubership.integration.platform.chain.impl.MaskedFieldImpl;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.io.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DependencyExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.MaskedFieldExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementsExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                .importChain(toImportChain(externalEntity))
                .existingChain(Chain.builder().build())
                .existingFolder(existingFolder)
                .build();
    }

    // ---- import: full entity graph characterization (regression oracle for the seam) ----

    @DisplayName("Import should copy every scalar field from the external content onto the chain")
    @Test
    void shouldImportScalarChainFields() {
        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .description("A description")
                .businessDescription("A business description")
                .assumptions("Some assumptions")
                .outOfScope("Out of scope notes")
                .lastImportHash("hash-42")
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        assertEquals("chain-1", result.getId());
        assertEquals("Payments", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("A business description", result.getBusinessDescription());
        assertEquals("Some assumptions", result.getAssumptions());
        assertEquals("Out of scope notes", result.getOutOfScope());
        assertEquals("hash-42", result.getLastImportHash());
    }

    @DisplayName("Import should turn each label name into a non-technical ChainLabel bound to the chain")
    @Test
    void shouldImportLabelsAsNonTechnicalAndBound() {
        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .labels(List.of("prod", "billing"))
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        Set<String> labelNames = result.getLabels().stream()
                .map(ChainLabel::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("prod", "billing"), labelNames);
        assertTrue(result.getLabels().stream().noneMatch(ChainLabel::isTechnical),
                "Imported labels must not be marked technical");
        assertTrue(result.getLabels().stream().allMatch(label -> label.getChain() == result),
                "Every imported label must reference the resulting chain");
    }

    @DisplayName("Import should turn each masked-field name into a MaskedField bound to the chain")
    @Test
    void shouldImportMaskedFieldsBoundToChain() {
        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .maskedFields(Set.of(
                        new MaskedFieldExternalEntity(null, "password"),
                        new MaskedFieldExternalEntity(null, "token")))
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        Set<String> maskedNames = result.getMaskedFields().stream()
                .map(MaskedField::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("password", "token"), maskedNames);
        assertTrue(result.getMaskedFields().stream().allMatch(field -> field.getChain() == result),
                "Every masked field must reference the resulting chain");
    }

    @DisplayName("Import should clear overriddenByChainId when the content is not marked as overridden")
    @Test
    void shouldClearOverriddenByChainIdWhenNotOverridden() {
        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .overridesChainId("overrides-1")
                .overriddenByChainId("overridden-by-1")
                .overridden(false)
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        assertEquals("overrides-1", result.getOverridesChainId());
        assertNull(result.getOverriddenByChainId(),
                "overriddenByChainId must be cleared when the chain is not overridden");
    }

    @DisplayName("Import should keep overriddenByChainId when the content is marked as overridden")
    @Test
    void shouldKeepOverriddenByChainIdWhenOverridden() {
        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .overridesChainId("overrides-1")
                .overriddenByChainId("overridden-by-1")
                .overridden(true)
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        assertEquals("overrides-1", result.getOverridesChainId());
        assertEquals("overridden-by-1", result.getOverriddenByChainId());
    }

    @DisplayName("Import should wire the default and reuse swimlanes from their ids and flag the elements")
    @Test
    void shouldWireDefaultAndReuseSwimlanes() {
        SwimlaneChainElement defaultSwimlane = swimlane("swimlane-default");
        SwimlaneChainElement reuseSwimlane = swimlane("swimlane-reuse");
        stubElementsMapperReturns(List.of(defaultSwimlane, reuseSwimlane));

        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .defaultSwimlaneId("swimlane-default")
                .reuseSwimlaneId("swimlane-reuse")
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        assertSame(defaultSwimlane, result.getDefaultSwimlane());
        assertSame(reuseSwimlane, result.getReuseSwimlane());
        assertTrue(result.getDefaultSwimlane().isDefaultSwimlane());
        assertTrue(result.getReuseSwimlane().isReuseSwimlane());
        assertSame(result, result.getDefaultSwimlane().getChain());
        assertSame(result, result.getReuseSwimlane().getChain());
    }

    @DisplayName("Import should leave both swimlanes null when the content declares no swimlane ids")
    @Test
    void shouldLeaveSwimlanesNullWhenNotDeclared() {
        stubElementsMapperReturns(List.of(swimlane("swimlane-1")));

        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(ChainExternalContentEntity.builder().build())
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        assertNull(result.getDefaultSwimlane());
        assertNull(result.getReuseSwimlane());
    }

    @DisplayName("Import should attach the element hierarchy, preserving container nesting and parent links")
    @Test
    void shouldImportElementHierarchy() {
        ContainerChainElement container = new ContainerChainElement();
        container.setId("container-1");
        container.setType("container");
        ChainElement child = new ChainElement();
        child.setId("child-1");
        child.setType("http-trigger");
        child.setName("Trigger");
        child.setProperties(Map.of("path", "/api"));
        container.addChildElement(child);
        stubElementsMapperReturns(List.of(container));

        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(ChainExternalContentEntity.builder().build())
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        // addElementsHierarchy flattens the tree onto the chain and binds every element to the chain.
        Map<String, ChainElement> byId = result.getElements().stream()
                .collect(Collectors.toMap(ChainElement::getId, element -> element));
        assertEquals(Set.of("container-1", "child-1"), byId.keySet());
        assertSame(result, byId.get("container-1").getChain());
        assertSame(result, byId.get("child-1").getChain());

        ContainerChainElement resultContainer = assertInstanceOf(ContainerChainElement.class, byId.get("container-1"));
        assertEquals(List.of("child-1"),
                resultContainer.getElements().stream().map(ChainElement::getId).toList());
        assertSame(resultContainer, byId.get("child-1").getParent());
        assertEquals("http-trigger", byId.get("child-1").getType());
        assertEquals("/api", byId.get("child-1").getProperty("path"));
    }

    @DisplayName("Import should wire input and output dependencies between the mapped elements")
    @Test
    void shouldImportDependenciesBetweenElements() {
        ChainElement from = new ChainElement();
        from.setId("from-1");
        from.setType("http-trigger");
        ChainElement to = new ChainElement();
        to.setId("to-1");
        to.setType("http-sender");
        stubElementsMapperReturns(List.of(from, to));

        ChainExternalContentEntity content = ChainExternalContentEntity.builder()
                .dependencies(List.of(new DependencyExternalEntity("from-1", "to-1")))
                .build();
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        Map<String, ChainElement> byId = result.getElements().stream()
                .collect(Collectors.toMap(ChainElement::getId, element -> element));
        List<Dependency> outputs = byId.get("from-1").getOutputDependencies();
        List<Dependency> inputs = byId.get("to-1").getInputDependencies();
        assertEquals(1, outputs.size());
        assertEquals(1, inputs.size());
        Dependency dependency = outputs.get(0);
        assertSame(dependency, inputs.get(0));
        assertSame(byId.get("from-1"), dependency.getElementFrom());
        assertSame(byId.get("to-1"), dependency.getElementTo());
        assertTrue(byId.get("from-1").getInputDependencies().isEmpty());
        assertTrue(byId.get("to-1").getOutputDependencies().isEmpty());
    }

    @DisplayName("Import should apply the folder path from metaInfo.group alongside the rest of the graph")
    @Test
    void shouldImportFolderPathWithinFullGraph() {
        ChainExternalEntity externalEntity = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .metaInfo(MetaInfoExternalEntity.builder().group("A/B").build())
                .content(ChainExternalContentEntity.builder().build())
                .build();

        Chain result = mapper.toInternalEntity(mapperEntity(externalEntity));

        Folder leaf = result.getParentFolder();
        assertEquals("B", leaf.getName());
        assertEquals("A", leaf.getParentFolder().getName());
        assertNull(leaf.getParentFolder().getParentFolder());
        assertFalse(result.getLabels().stream().anyMatch(ChainLabel::isTechnical));
    }

    private void stubElementsMapperReturns(List<ChainElement> rootElements) {
        when(chainElementsMapper.toInternalEntity(any())).thenReturn(rootElements);
    }

    private SwimlaneChainElement swimlane(String id) {
        SwimlaneChainElement swimlane = new SwimlaneChainElement();
        swimlane.setId(id);
        swimlane.setType("swimlane");
        return swimlane;
    }

    private ChainExternalMapperEntity mapperEntity(ChainExternalEntity externalEntity) {
        return ChainExternalMapperEntity.builder()
                .importChain(toImportChain(externalEntity))
                .existingChain(new Chain())
                .build();
    }

    // The seam now consumes the library ImportChain model. These helpers translate the DTO the tests
    // still build into that model, so the entity-graph assertions stay unchanged.

    private ChainImpl toImportChain(ChainExternalEntity externalEntity) {
        ChainExternalContentEntity content = externalEntity.getContent();
        ChainImpl importChain = new ChainImpl();
        importChain.setId(externalEntity.getId());
        importChain.setName(externalEntity.getName());
        importChain.setDescription(content.getDescription());
        importChain.setBusinessDescription(content.getBusinessDescription());
        importChain.setAssumptions(content.getAssumptions());
        importChain.setOutOfScope(content.getOutOfScope());
        importChain.setLastImportHash(content.getLastImportHash());
        importChain.setOverridesChainId(content.getOverridesChainId());
        importChain.setOverridden(content.isOverridden());
        importChain.setOverriddenByChainId(content.getOverriddenByChainId());
        importChain.setLabels(content.getLabels() == null
                ? List.of()
                : content.getLabels().stream().map(name -> (Label) new LabelImpl(name, false)).toList());
        importChain.setMaskedFields(content.getMaskedFields() == null
                ? List.of()
                : content.getMaskedFields().stream().<org.qubership.integration.platform.chain.model.MaskedField>map(this::toModelMaskedField).toList());
        importChain.setElements(new ArrayList<>());
        importChain.setConnections(content.getDependencies() == null
                ? List.of()
                : content.getDependencies().stream().<Connection>map(this::toConnection).toList());
        importChain.setParentFolder(modelFolders(externalEntity.getMetaInfo()));
        if (content.getDefaultSwimlaneId() != null) {
            importChain.setDefaultSwimlane(modelElement(content.getDefaultSwimlaneId()));
        }
        if (content.getReuseSwimlaneId() != null) {
            importChain.setReuseSwimlane(modelElement(content.getReuseSwimlaneId()));
        }
        return importChain;
    }

    private MaskedFieldImpl toModelMaskedField(MaskedFieldExternalEntity external) {
        MaskedFieldImpl maskedField = new MaskedFieldImpl();
        maskedField.setId(external.getId());
        maskedField.setName(external.getName());
        return maskedField;
    }

    private ConnectionImpl toConnection(DependencyExternalEntity dependency) {
        return new ConnectionImpl(modelElement(dependency.getFrom()), modelElement(dependency.getTo()));
    }

    private ElementImpl modelElement(String id) {
        ElementImpl element = new ElementImpl();
        element.setId(id);
        return element;
    }

    private FolderImpl modelFolders(MetaInfoExternalEntity metaInfo) {
        if (metaInfo == null || metaInfo.getGroup() == null) {
            return null;
        }
        FolderImpl parent = null;
        FolderImpl current = null;
        for (String segment : metaInfo.getGroup().split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            current = new FolderImpl();
            current.setName(segment);
            current.setParentFolder(parent);
            parent = current;
        }
        return current;
    }
}
