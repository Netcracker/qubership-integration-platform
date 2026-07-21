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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.impl.ServiceEnvironmentImpl;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementsExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainElementsExternalEntityMapperTest {

    private LibraryElementsService libraryService;
    private ChainElementsExternalEntityMapper mapper;

    @BeforeEach
    void setUp() {
        libraryService = mock(LibraryElementsService.class);
        // A real substitutor is a no-op for elements whose properties do not opt into separate files,
        // so the mapper output stays the honest oracle here.
        ChainElementFilePropertiesSubstitutor substitutor =
                new ChainElementFilePropertiesSubstitutor(new ObjectMapper());
        mapper = new ChainElementsExternalEntityMapper(libraryService, substitutor);
    }

    private void stubDescriptor(String type, ElementType elementType, boolean container) {
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(elementType);
        descriptor.setContainer(container);
        lenient().when(libraryService.lookupElementDescriptor(type)).thenReturn(Optional.of(descriptor));
    }

    // ---- toInternalEntity: the library model tree -> JPA element tree ----

    @DisplayName("Builds a plain leaf element and copies every scalar field from the model")
    @Test
    void mapsScalarFieldsOfLeafElement() {
        stubDescriptor("http-sender", ElementType.MODULE, false);
        ElementImpl model = new ElementImpl();
        model.setId("el-1");
        model.setType("http-sender");
        model.setName("Sender");
        model.setDescription("Sends requests");
        model.setOriginalId("origin-1");
        model.setProperties(new HashMap<>(Map.of("path", "/api")));

        List<ChainElement> result = mapper.toInternalEntity(List.of(model));

        assertEquals(1, result.size());
        ChainElement element = result.get(0);
        assertEquals(ChainElement.class, element.getClass());
        assertEquals("el-1", element.getId());
        assertEquals("http-sender", element.getType());
        assertEquals("Sender", element.getName());
        assertEquals("Sends requests", element.getDescription());
        assertEquals("origin-1", element.getOriginalId());
        assertEquals("/api", element.getProperty("path"));
        assertNull(element.getEnvironment());
        assertNotNull(element.getCreatedWhen(), "createdWhen must be re-stamped because the model omits it");
    }

    @DisplayName("Leaves originalId null when the model carries no original id")
    @Test
    void mapsMissingOriginalIdToNull() {
        stubDescriptor("http-sender", ElementType.MODULE, false);
        ElementImpl model = new ElementImpl();
        model.setId("el-1");
        model.setType("http-sender");

        ChainElement element = mapper.toInternalEntity(List.of(model)).get(0);

        assertNull(element.getOriginalId());
    }

    @DisplayName("Rebuilds container nesting from children and keeps only roots at the top level")
    @Test
    void mapsNestedContainerElementsWithParentLinks() {
        stubDescriptor("container", ElementType.CONTAINER, true);
        stubDescriptor("http-sender", ElementType.MODULE, false);

        ElementImpl child = new ElementImpl();
        child.setId("child-1");
        child.setType("http-sender");
        ElementImpl container = new ElementImpl();
        container.setId("container-1");
        container.setType("container");
        container.setContainer(true);
        container.setChildren(List.of(child));
        // The flat model list carries children too; each child points back to its parent so the
        // root pass skips it and the container rebuilds the nesting from getChildren().
        child.setParent(container);

        List<ChainElement> result = mapper.toInternalEntity(List.of(container, child));

        assertEquals(1, result.size(), "only the root container is returned at the top level");
        ContainerChainElement resultContainer = assertInstanceOf(ContainerChainElement.class, result.get(0));
        assertEquals(List.of("child-1"),
                resultContainer.getElements().stream().map(ChainElement::getId).toList());
        ChainElement resultChild = resultContainer.getElements().get(0);
        assertSame(resultContainer, resultChild.getParent());
        assertEquals(ChainElement.class, resultChild.getClass());
    }

    @DisplayName("Wires swimlane membership by id regardless of iteration order")
    @Test
    void wiresSwimlaneMembershipInSecondPass() {
        stubDescriptor("swimlane", ElementType.SWIMLANE, false);
        stubDescriptor("http-sender", ElementType.MODULE, false);

        ElementImpl swimlaneModel = new ElementImpl();
        swimlaneModel.setId("lane-1");
        swimlaneModel.setType("swimlane");
        ElementImpl member = new ElementImpl();
        member.setId("member-1");
        member.setType("http-sender");
        member.setSwimlane(swimlaneModel);

        // The member precedes its lane so the second pass, not creation order, must resolve the link.
        List<ChainElement> result = mapper.toInternalEntity(List.of(member, swimlaneModel));

        Map<String, ChainElement> byId = result.stream()
                .collect(java.util.stream.Collectors.toMap(ChainElement::getId, e -> e));
        SwimlaneChainElement lane = assertInstanceOf(SwimlaneChainElement.class, byId.get("lane-1"));
        assertSame(lane, byId.get("member-1").getSwimlane());
    }

    @DisplayName("Maps a service environment field by field and inverts the activation flag")
    @Test
    void mapsServiceEnvironmentAndInvertsActivation() {
        stubDescriptor("service-call", ElementType.MODULE, false);
        ServiceEnvironmentImpl env = new ServiceEnvironmentImpl();
        env.setId("env-1");
        env.setName("Prod");
        env.setDescription("Production");
        env.setSystemId("sys-1");
        env.setAddress("http://prod");
        env.setSourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER);
        env.setProperties(new HashMap<>(Map.of("timeout", 30)));
        env.setActivated(true);
        env.setCreatedWhen(111L);
        env.setModifiedWhen(222L);

        ElementImpl model = new ElementImpl();
        model.setId("el-1");
        model.setType("service-call");
        model.setServiceEnvironment(env);

        ChainElement element = mapper.toInternalEntity(List.of(model)).get(0);

        org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment mapped =
                element.getEnvironment();
        assertNotNull(mapped);
        assertEquals("env-1", mapped.getId());
        assertEquals("Prod", mapped.getName());
        assertEquals("Production", mapped.getDescription());
        assertEquals("sys-1", mapped.getSystemId());
        assertEquals("http://prod", mapped.getAddress());
        assertEquals(EnvironmentSourceType.MAAS_BY_CLASSIFIER, mapped.getSourceType());
        assertEquals(30, mapped.getProperties().get("timeout"));
        assertFalse(mapped.isNotActivated(), "an activated model must map to notActivated=false");
        assertEquals(111L, mapped.getCreatedWhen());
        assertEquals(222L, mapped.getModifiedWhen());
    }

    @DisplayName("Falls back to a container descriptor for the container type when the library lacks one")
    @Test
    void fallsBackToContainerDescriptorForUnknownContainer() {
        when(libraryService.lookupElementDescriptor("container")).thenReturn(Optional.empty());
        ElementImpl model = new ElementImpl();
        model.setId("container-1");
        model.setType("container");
        model.setContainer(true);

        ChainElement element = mapper.toInternalEntity(List.of(model)).get(0);

        assertInstanceOf(ContainerChainElement.class, element);
    }

    @DisplayName("Rejects an element whose type is neither known nor the container fallback")
    @Test
    void rejectsUnknownElementType() {
        when(libraryService.lookupElementDescriptor("mystery")).thenReturn(Optional.empty());
        ElementImpl model = new ElementImpl();
        model.setId("el-1");
        model.setType("mystery");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> mapper.toInternalEntity(List.of(model)));
        assertTrue(error.getMessage().contains("mystery"));
    }

    // ---- toExternalEntity: the JPA element tree -> external DTO tree ----

    @DisplayName("Serializes only root elements and nests container children under them")
    @Test
    void mapsRootElementsWithNestedChildrenToExternal() {
        ContainerChainElement container = new ContainerChainElement();
        container.setId("container-1");
        container.setType("container");
        container.setName("Group");
        ChainElement child = new ChainElement();
        child.setId("child-1");
        child.setType("http-sender");
        child.setName("Sender");
        child.setDescription("desc");
        child.setOriginalId("origin-1");
        container.addChildElement(child);

        ChainElementsExternalMapperEntity result =
                mapper.toExternalEntity(List.of(container, child));

        List<ChainElementExternalEntity> roots = result.getChainElementExternalEntities();
        assertEquals(1, roots.size(), "the child is nested, not a second root");
        ChainElementExternalEntity externalContainer = roots.get(0);
        assertEquals("container-1", externalContainer.getId());
        assertEquals("container", externalContainer.getType());
        assertEquals("Group", externalContainer.getName());
        assertEquals(1, externalContainer.getChildren().size());
        ChainElementExternalEntity externalChild = externalContainer.getChildren().get(0);
        assertEquals("child-1", externalChild.getId());
        assertEquals("http-sender", externalChild.getType());
        assertEquals("Sender", externalChild.getName());
        assertEquals("desc", externalChild.getDescription());
        assertEquals("origin-1", externalChild.getOriginalId());
        assertTrue(result.getElementPropertyFiles().isEmpty());
    }

    @DisplayName("Writes the swimlane id from the element's swimlane reference")
    @Test
    void writesSwimlaneIdFromReference() {
        SwimlaneChainElement swimlane = new SwimlaneChainElement();
        swimlane.setId("lane-1");
        ChainElement element = new ChainElement();
        element.setId("el-1");
        element.setType("http-sender");
        element.setSwimlane(swimlane);

        ChainElementExternalEntity external =
                mapper.toExternalEntity(List.of(element)).getChainElementExternalEntities().get(0);

        assertEquals("lane-1", external.getSwimlaneId());
    }

    @DisplayName("Leaves the swimlane id null when the element belongs to no lane")
    @Test
    void leavesSwimlaneIdNullWhenAbsent() {
        ChainElement element = new ChainElement();
        element.setId("el-1");
        element.setType("http-sender");

        ChainElementExternalEntity external =
                mapper.toExternalEntity(List.of(element)).getChainElementExternalEntities().get(0);

        assertNull(external.getSwimlaneId());
    }

    @DisplayName("Sorts the roles property while leaving the rest of the properties intact")
    @Test
    void sortsRolesPropertyOnExport() {
        ChainElement element = new ChainElement();
        element.setId("el-1");
        element.setType("http-sender");
        Map<String, Object> properties = new HashMap<>();
        properties.put("roles", List.of("gamma", "alpha", "beta"));
        properties.put("path", "/api");
        element.setProperties(properties);

        ChainElementExternalEntity external =
                mapper.toExternalEntity(List.of(element)).getChainElementExternalEntities().get(0);

        assertEquals(List.of("alpha", "beta", "gamma"), external.getProperties().get("roles"));
        assertEquals("/api", external.getProperties().get("path"));
    }

    @DisplayName("preSortProperties leaves a non-list roles value untouched")
    @Test
    void preSortPropertiesIgnoresNonListRoles() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("roles", "admin");

        Map<String, Object> result = ChainElementsExternalEntityMapper.preSortProperties(properties);

        assertEquals("admin", result.get("roles"));
    }
}
