/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.io.readers.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.Folder;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.io.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DependencyExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.MaskedFieldExternalEntity;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.configuration.ElementDescriptorProperties;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.util.PropertyPlaceholderHelper;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainModelMapperTest {

    private ChainModelMapper mapper;

    @BeforeEach
    void setUp() {
        LibraryElementsService libraryService = new LibraryElementsService(
                new YAMLMapper(),
                new PropertyPlaceholderHelper("${", "}"),
                new ElementDescriptorProperties(new Properties()));
        libraryService.registerElement(descriptor("http-trigger", ElementType.TRIGGER, false));
        libraryService.registerElement(descriptor("http-sender", ElementType.MODULE, false));
        libraryService.registerElement(descriptor("service-call", ElementType.MODULE, false));
        libraryService.registerElement(descriptor("swimlane", ElementType.SWIMLANE, false));

        ChainElementPropertiesSubstitutor substitutor = new ChainElementPropertiesSubstitutor(new ObjectMapper());
        mapper = new ChainModelMapper(libraryService, substitutor);
    }

    @DisplayName("Import copies every scalar chain field from the external content")
    @Test
    void mapsScalarChainFields() {
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .description("A description")
                .businessDescription("A business description")
                .assumptions("Some assumptions")
                .outOfScope("Out of scope notes")
                .build());

        ImportChain result = mapper.map(external, null);

        assertEquals("chain-1", result.getId());
        assertEquals("Payments", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("A business description", result.getBusinessDescription());
        assertEquals("Some assumptions", result.getAssumptions());
        assertEquals("Out of scope notes", result.getOutOfScope());
    }

    @DisplayName("Import fills the ImportChain-only fields and clears overriddenByChainId when not overridden")
    @Test
    void mapsImportOnlyFieldsWithoutOverride() {
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .lastImportHash("hash-42")
                .overridesChainId("overrides-1")
                .overriddenByChainId("overridden-by-1")
                .overridden(false)
                .deployments(List.of(
                        DeploymentExternalEntity.builder().domain("default").build(),
                        DeploymentExternalEntity.builder().domain("secure").build()))
                .deployAction(ChainCommitRequestAction.DEPLOY)
                .build());

        ImportChain result = mapper.map(external, null);

        assertEquals("hash-42", result.getLastImportHash());
        assertEquals("overrides-1", result.getOverridesChainId());
        assertFalse(result.isOverridden());
        assertNull(result.getOverriddenByChainId(), "overriddenByChainId must be cleared when not overridden");
        assertEquals(List.of("default", "secure"), result.getDeployments());
        assertEquals(ChainCommitRequestAction.DEPLOY, result.getDeployAction());
    }

    @DisplayName("Import keeps overriddenByChainId when the content is marked as overridden")
    @Test
    void keepsOverriddenByChainIdWhenOverridden() {
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .overridesChainId("overrides-1")
                .overriddenByChainId("overridden-by-1")
                .overridden(true)
                .build());

        ImportChain result = mapper.map(external, null);

        assertTrue(result.isOverridden());
        assertEquals("overridden-by-1", result.getOverriddenByChainId());
    }

    @DisplayName("Import turns each label into a non-technical label")
    @Test
    void mapsLabelsAsNonTechnical() {
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .labels(List.of("prod", "billing"))
                .build());

        ImportChain result = mapper.map(external, null);

        Set<String> names = result.getLabels().stream().map(Label::getName).collect(Collectors.toSet());
        assertEquals(Set.of("prod", "billing"), names);
        assertTrue(result.getLabels().stream().noneMatch(Label::isTechnical));
    }

    @DisplayName("Import turns each masked-field name into a masked field")
    @Test
    void mapsMaskedFieldsByName() {
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .maskedFields(Set.of(
                        new MaskedFieldExternalEntity(null, "password"),
                        new MaskedFieldExternalEntity(null, "token")))
                .build());

        ImportChain result = mapper.map(external, null);

        Set<String> names = result.getMaskedFields().stream()
                .map(org.qubership.integration.platform.chain.model.MaskedField::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("password", "token"), names);
    }

    @DisplayName("Import builds a folder hierarchy from metaInfo.group, leaf first")
    @Test
    void mapsGroupToFolderHierarchy() {
        ChainExternalEntity external = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .metaInfo(MetaInfoExternalEntity.builder().group("A/B/C").build())
                .content(ChainExternalContentEntity.builder().build())
                .build();

        ImportChain result = mapper.map(external, null);

        Folder leaf = result.getParentFolder().orElseThrow();
        assertEquals("C", leaf.getName());
        assertEquals("B", leaf.getParentFolder().orElseThrow().getName());
        assertEquals("A", leaf.getParentFolder().orElseThrow().getParentFolder().orElseThrow().getName());
        assertTrue(leaf.getParentFolder().orElseThrow().getParentFolder().orElseThrow().getParentFolder().isEmpty());
    }

    @DisplayName("Import leaves the chain at the root when the group is empty")
    @Test
    void mapsEmptyGroupToRoot() {
        ChainExternalEntity external = ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .metaInfo(MetaInfoExternalEntity.builder().group("").build())
                .content(ChainExternalContentEntity.builder().build())
                .build();

        ImportChain result = mapper.map(external, null);

        assertTrue(result.getParentFolder().isEmpty());
    }

    @DisplayName("Import flattens the element tree, keeping container nesting, parent links, and chain binding")
    @Test
    void mapsElementHierarchy() {
        ChainElementExternalEntity child = ChainElementExternalEntity.builder()
                .id("child-1")
                .type("http-sender")
                .name("Sender")
                .properties(Map.of("path", "/out"))
                .build();
        ChainElementExternalEntity container = ChainElementExternalEntity.builder()
                .id("container-1")
                .type("container")
                .children(List.of(child))
                .build();

        ImportChain result = mapper.map(chain(container), null);

        Map<String, Element> byId = result.getElements().stream()
                .collect(Collectors.toMap(Element::getId, element -> element));
        assertEquals(Set.of("container-1", "child-1"), byId.keySet());
        assertSame(result, byId.get("container-1").getChain());
        assertSame(result, byId.get("child-1").getChain());

        Element resultContainer = byId.get("container-1");
        assertTrue(resultContainer.isContainer());
        assertEquals(List.of("child-1"),
                resultContainer.getChildren().stream().map(Element::getId).toList());
        assertSame(resultContainer, byId.get("child-1").getParent().orElseThrow());
        assertEquals("http-sender", byId.get("child-1").getType());
        assertEquals("/out", byId.get("child-1").getProperties().get("path"));
    }

    @DisplayName("Import wires a service-call element's serviceEnvironment and originalId")
    @Test
    void mapsServiceEnvironmentAndOriginalId() {
        org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment env =
                new org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment();
        env.setId("env-1");
        env.setName("Prod");
        env.setSystemId("system-9");
        env.setAddress("https://api.example.com");
        env.setSourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER);
        env.setProperties(Map.of("timeout", 30));
        env.setNotActivated(true);
        env.setCreatedWhen(1_700_000_000_000L);
        env.setModifiedWhen(1_700_000_500_000L);

        ChainElementExternalEntity serviceCall = ChainElementExternalEntity.builder()
                .id("sc-1")
                .type("service-call")
                .originalId("origin-7")
                .serviceEnvironment(env)
                .build();

        ImportChain result = mapper.map(chain(serviceCall), null);

        Element element = result.getElements().iterator().next();
        assertEquals("origin-7", element.getOriginalId().orElseThrow());
        ServiceEnvironment mapped = element.getEnvironment().orElseThrow();
        assertEquals("env-1", mapped.getId());
        assertEquals("system-9", mapped.getSystemId());
        assertEquals("https://api.example.com", mapped.getAddress());
        assertEquals(EnvironmentSourceType.MAAS_BY_CLASSIFIER, mapped.getSourceType());
        assertEquals(30, mapped.getProperties().get("timeout"));
        assertFalse(mapped.isActivated(), "notActivated=true must map to activated=false");
        assertEquals(1_700_000_000_000L, mapped.getCreatedWhen(), "createdWhen must survive the import round-trip");
        assertEquals(1_700_000_500_000L, mapped.getModifiedWhen(), "modifiedWhen must survive the import round-trip");
    }

    @DisplayName("Import wires default and reuse swimlanes and per-element swimlane membership")
    @Test
    void mapsSwimlanes() {
        ChainElementExternalEntity defaultSwimlane = ChainElementExternalEntity.builder()
                .id("sl-default").type("swimlane").build();
        ChainElementExternalEntity reuseSwimlane = ChainElementExternalEntity.builder()
                .id("sl-reuse").type("swimlane").build();
        ChainElementExternalEntity member = ChainElementExternalEntity.builder()
                .id("member-1").type("http-sender").swimlaneId("sl-default").build();

        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .defaultSwimlaneId("sl-default")
                .reuseSwimlaneId("sl-reuse")
                .elements(List.of(defaultSwimlane, reuseSwimlane, member))
                .build());

        ImportChain result = mapper.map(external, null);

        Element defaultLane = result.getDefaultSwimlane().orElseThrow();
        Element reuseLane = result.getReuseSwimlane().orElseThrow();
        assertEquals("sl-default", defaultLane.getId());
        assertEquals("sl-reuse", reuseLane.getId());
        assertTrue(assertInstanceOf(ElementImpl.class, defaultLane).isSwimlane());
        assertTrue(assertInstanceOf(ElementImpl.class, reuseLane).isSwimlane());

        Element member1 = result.getElements().stream()
                .filter(element -> "member-1".equals(element.getId())).findFirst().orElseThrow();
        assertSame(defaultLane, member1.getSwimlane().orElseThrow());
    }

    @DisplayName("Import rejects a default swimlane id that is not a swimlane element")
    @Test
    void rejectsUnknownDefaultSwimlane() {
        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("e-1").type("http-sender").build();
        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .defaultSwimlaneId("missing")
                .elements(List.of(element))
                .build());

        assertThrows(IllegalArgumentException.class, () -> mapper.map(external, null));
    }

    @DisplayName("Import wires dependencies into element input and output connections, resolving container children")
    @Test
    void mapsDependenciesToConnections() {
        ChainElementExternalEntity trigger = ChainElementExternalEntity.builder()
                .id("trigger-1").type("http-trigger").build();
        ChainElementExternalEntity child = ChainElementExternalEntity.builder()
                .id("child-1").type("http-sender").build();
        ChainElementExternalEntity container = ChainElementExternalEntity.builder()
                .id("container-1").type("container").children(List.of(child)).build();

        ChainExternalEntity external = chain(ChainExternalContentEntity.builder()
                .elements(List.of(trigger, container))
                .dependencies(List.of(new DependencyExternalEntity("trigger-1", "child-1")))
                .build());

        ImportChain result = mapper.map(external, null);

        Map<String, Element> byId = result.getElements().stream()
                .collect(Collectors.toMap(Element::getId, element -> element));
        Element from = byId.get("trigger-1");
        Element to = byId.get("child-1");
        assertEquals(1, from.getOutputConnections().size());
        assertEquals(1, to.getInputConnections().size());
        Connection connection = from.getOutputConnections().iterator().next();
        assertSame(connection, to.getInputConnections().iterator().next());
        assertSame(from, connection.getFrom());
        assertSame(to, connection.getTo());
        assertTrue(from.getInputConnections().isEmpty());
        assertTrue(to.getOutputConnections().isEmpty());
        assertEquals(1, result.getConnections().size());
    }

    @DisplayName("Import restores an element property exported to a separate JSON file")
    @Test
    void mapsFileBackedProperty() {
        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("e-file")
                .type("http-sender")
                .propertiesFilename("config.json")
                .build();
        PropertyFileSource fileSource = fileName ->
                "config.json".equals(fileName) ? "{\"host\":\"db\",\"port\":5432}" : null;

        ImportChain result = mapper.map(chain(element), fileSource);

        Map<String, Object> properties = result.getElements().iterator().next().getProperties();
        assertEquals("db", properties.get("host"));
        assertEquals(5432, properties.get("port"));
        assertFalse(properties.containsKey("propertiesFilename"),
                "the file-name marker must not leak into the restored properties");
    }

    private static ElementDescriptor descriptor(String name, ElementType type, boolean container) {
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setName(name);
        descriptor.setType(type);
        descriptor.setContainer(container);
        return descriptor;
    }

    private static ChainExternalEntity chain(ChainExternalContentEntity content) {
        return ChainExternalEntity.builder()
                .id("chain-1")
                .name("Payments")
                .content(content)
                .build();
    }

    private static ChainExternalEntity chain(ChainElementExternalEntity singleElement) {
        return chain(ChainExternalContentEntity.builder().elements(List.of(singleElement)).build());
    }
}
