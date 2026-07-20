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

import org.qubership.integration.platform.chain.impl.*;
import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.io.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DependencyExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.MaskedFieldExternalEntity;
import org.qubership.integration.platform.io.readers.migrations.common.GroupPathUtils;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.io.readers.chain.ImportConstants.CONTAINER;

/**
 * Builds an {@link ImportChain} from a deserialized chain export.
 *
 * <p>Mirrors the pure construction the catalog performs in {@code ChainExternalEntityMapper} and
 * {@code ChainElementsExternalEntityMapper}: it produces the library chain model directly instead of
 * JPA entities. It reproduces only the build-from-DTO path. The catalog's merge with an existing
 * chain, dependency reuse, and database folder reuse stay in the catalog for a later step.
 */
@Component
public class ChainModelMapper {

    private final LibraryElementsService libraryService;
    private final ChainElementPropertiesSubstitutor propertiesSubstitutor;

    public ChainModelMapper(LibraryElementsService libraryService, ChainElementPropertiesSubstitutor propertiesSubstitutor) {
        this.libraryService = libraryService;
        this.propertiesSubstitutor = propertiesSubstitutor;
    }

    /**
     * Maps an external chain to an {@link ImportChain}, restoring file-backed element properties from
     * {@code fileSource}. Pass {@code null} for {@code fileSource} to skip property-file substitution.
     */
    public ImportChain map(ChainExternalEntity externalChain, @Nullable PropertyFileSource fileSource) {
        ChainExternalContentEntity content = externalChain.getContent();
        ChainImpl chain = new ChainImpl();

        chain.setId(externalChain.getId());
        chain.setName(externalChain.getName());
        chain.setDescription(content.getDescription());
        chain.setBusinessDescription(content.getBusinessDescription());
        chain.setAssumptions(content.getAssumptions());
        chain.setOutOfScope(content.getOutOfScope());

        chain.setLastImportHash(content.getLastImportHash());
        chain.setOverridesChainId(content.getOverridesChainId());
        chain.setOverridden(content.isOverridden());
        chain.setOverriddenByChainId(content.isOverridden() ? content.getOverriddenByChainId() : null);
        chain.setDeployAction(content.getDeployAction());
        chain.setDeployments(extractDeployments(content.getDeployments()));

        chain.setLabels(createLabels(content.getLabels()));
        chain.setMaskedFields(createMaskedFields(content.getMaskedFields()));
        chain.setParentFolder(createParentFolder(externalChain.getMetaInfo()));

        Map<String, ElementImpl> elementsById = buildElements(content.getElements(), fileSource);
        elementsById.values().forEach(element -> element.setChain(chain));
        chain.setElements(new ArrayList<>(elementsById.values()));

        specifySwimlanes(content, elementsById, chain);
        chain.setConnections(buildConnections(content.getDependencies(), elementsById));

        return chain;
    }

    private Map<String, ElementImpl> buildElements(List<ChainElementExternalEntity> externalElements, @Nullable PropertyFileSource fileSource) {
        Map<String, ElementImpl> resultElements = new LinkedHashMap<>();
        if (externalElements == null) {
            return resultElements;
        }

        // Swimlanes first, so later elements can resolve their swimlane membership by id.
        externalElements.stream()
                .filter(external -> isSwimlaneType(external.getType()))
                .forEach(external -> createElement(external, fileSource, resultElements));
        externalElements.stream()
                .filter(external -> !isSwimlaneType(external.getType()))
                .forEach(external -> createElement(external, fileSource, resultElements));

        return resultElements;
    }

    private ElementImpl createElement(
            ChainElementExternalEntity external,
            @Nullable PropertyFileSource fileSource,
            Map<String, ElementImpl> resultElements
    ) {
        ElementDescriptor descriptor = resolveDescriptor(external.getType());

        ElementImpl element = new ElementImpl();
        element.setContainer(descriptor.isContainer());
        if (descriptor.getType() == ElementType.SWIMLANE) {
            element.setSwimlaneElement(true);
        }

        if (descriptor.isContainer()) {
            List<Element> children = new ArrayList<>();
            for (ChainElementExternalEntity childExternal : external.getChildren()) {
                ElementImpl child = createElement(childExternal, fileSource, resultElements);
                child.setParent(element);
                children.add(child);
            }
            element.setChildren(children);
        }

        propertiesSubstitutor.enrichElementWithFileProperties(external, fileSource);

        element.setId(external.getId());
        element.setType(external.getType());
        element.setName(external.getName());
        element.setDescription(external.getDescription());
        element.setOriginalId(external.getOriginalId());
        element.setServiceEnvironment(mapEnvironment(external.getServiceEnvironment()));
        element.setProperties(external.getProperties());

        ElementImpl swimlane = resultElements.get(external.getSwimlaneId());
        if (swimlane != null && swimlane.isSwimlane()) {
            element.setSwimlane(swimlane);
        }

        resultElements.put(element.getId(), element);
        return element;
    }

    private ElementDescriptor resolveDescriptor(String type) {
        return libraryService.lookupElementDescriptor(type)
                .orElseGet(() -> {
                    if (CONTAINER.equals(type)) {
                        ElementDescriptor containerDescriptor = new ElementDescriptor();
                        containerDescriptor.setType(ElementType.CONTAINER);
                        containerDescriptor.setContainer(true);
                        return containerDescriptor;
                    }
                    throw new IllegalArgumentException("Element of type " + type + " not found");
                });
    }

    private boolean isSwimlaneType(String type) {
        return libraryService.lookupElementDescriptor(type)
                .map(descriptor -> descriptor.getType() == ElementType.SWIMLANE)
                .orElse(false);
    }

    private ServiceEnvironment mapEnvironment(
            @Nullable org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment external
    ) {
        if (external == null) {
            return null;
        }
        return ServiceEnvironmentBuilder.createNew()
                .id(external.getId())
                .name(external.getName())
                .description(external.getDescription())
                .systemId(external.getSystemId())
                .address(external.getAddress())
                .sourceType(external.getSourceType())
                .properties(external.getProperties())
                .activated(!external.isNotActivated())
                .createdWhen(external.getCreatedWhen())
                .modifiedWhen(external.getModifiedWhen())
                .build();
    }

    private void specifySwimlanes(ChainExternalContentEntity content, Map<String, ElementImpl> elementsById, ChainImpl chain) {
        chain.setDefaultSwimlane(null);
        chain.setReuseSwimlane(null);

        if (content.getDefaultSwimlaneId() != null) {
            chain.setDefaultSwimlane(requireSwimlane(elementsById, content.getDefaultSwimlaneId(), "Default"));
        }
        if (content.getReuseSwimlaneId() != null) {
            chain.setReuseSwimlane(requireSwimlane(elementsById, content.getReuseSwimlaneId(), "Reuse"));
        }
    }

    private ElementImpl requireSwimlane(Map<String, ElementImpl> elementsById, String swimlaneId, String role) {
        ElementImpl swimlane = elementsById.get(swimlaneId);
        if (swimlane == null || !swimlane.isSwimlane()) {
            throw new IllegalArgumentException(role + " swimlane " + swimlaneId + " not found");
        }
        return swimlane;
    }

    private Collection<Connection> buildConnections(List<DependencyExternalEntity> dependencies, Map<String, ElementImpl> elementsById) {
        List<Connection> connections = new ArrayList<>();
        if (dependencies == null) {
            return connections;
        }

        for (DependencyExternalEntity dependency : dependencies) {
            ElementImpl from = elementsById.get(dependency.getFrom());
            ElementImpl to = elementsById.get(dependency.getTo());
            if (from == null || to == null) {
                throw new IllegalArgumentException("Unable to create dependency. At least one element not found: " + dependency);
            }

            ConnectionImpl connection = new ConnectionImpl(from, to);
            from.getOutputConnections().add(connection);
            to.getInputConnections().add(connection);
            connections.add(connection);
        }

        return connections;
    }

    private Collection<Label> createLabels(@Nullable List<String> labels) {
        List<Label> result = new ArrayList<>();
        if (labels == null) {
            return result;
        }
        labels.forEach(name -> result.add(new LabelImpl(name, false)));
        return result;
    }

    private Collection<MaskedField> createMaskedFields(@Nullable Set<MaskedFieldExternalEntity> maskedFields) {
        List<MaskedField> result = new ArrayList<>();
        if (maskedFields == null) {
            return result;
        }
        for (MaskedFieldExternalEntity external : maskedFields) {
            MaskedFieldImpl maskedField = new MaskedFieldImpl();
            maskedField.setName(external.getName());
            result.add(maskedField);
        }
        return result;
    }

    @Nullable
    private Folder createParentFolder(@Nullable MetaInfoExternalEntity metaInfo) {
        List<String> segments = GroupPathUtils.parseSegments(metaInfo == null ? null : metaInfo.getGroup());
        if (segments.isEmpty()) {
            return null;
        }

        FolderImpl parent = null;
        FolderImpl current = null;
        for (String segment : segments) {
            current = new FolderImpl();
            current.setName(segment);
            current.setParentFolder(parent);
            parent = current;
        }
        return current;
    }

    private List<String> extractDeployments(@Nullable List<DeploymentExternalEntity> deployments) {
        List<String> result = new ArrayList<>();
        if (deployments == null) {
            return result;
        }
        deployments.stream()
                .map(DeploymentExternalEntity::getDomain)
                .forEach(result::add);
        return result;
    }
}
