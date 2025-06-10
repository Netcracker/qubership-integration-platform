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

import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.*;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.util.DistinctByKey;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ChainFileMigrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ChainExternalEntityMapper implements ExternalEntityMapper<Chain, ChainExternalMapperEntity> {

    private final ChainElementsExternalEntityMapper chainElementsMapper;
    private final ChainFileMigrationService chainFileMigrationService;
    private final URI chainSchemaUri;

    @Autowired
    public ChainExternalEntityMapper(
            ChainElementsExternalEntityMapper chainElementsMapper,
            ChainFileMigrationService chainFileMigrationService,
            @Value("${qip.json.schemas.chain:http://qubership.org/schemas/product/qip/chain}") URI chainSchemaUri
    ) {
        this.chainElementsMapper = chainElementsMapper;
        this.chainFileMigrationService = chainFileMigrationService;
        this.chainSchemaUri = chainSchemaUri;
    }

    @Override
    public Chain toInternalEntity(@NonNull ChainExternalMapperEntity externalMapperEntity) {
        ChainExternalEntity externalChain = externalMapperEntity.getChainExternalEntity();
        Chain resultChain = Optional.ofNullable(externalMapperEntity.getExistingChain())
                .orElse(new Chain());

        Set<Dependency> currentDependencies = resultChain.getDependencies();

        resultChain.setId(externalChain.getId());
        resultChain.setName(externalChain.getName());
        resultChain.setLastImportHash(externalChain.getContent().getLastImportHash());
        resultChain.setDescription(externalChain.getContent().getDescription());
        resultChain.setOverridesChainId(externalChain.getContent().getOverridesChainId());
        resultChain.setOverriddenByChainId(externalChain.getContent().getOverriddenByChainId());
        if (!externalChain.getContent().isOverridden()) {
            resultChain.setOverriddenByChainId(null);
        }

        resultChain.setBusinessDescription(externalChain.getContent().getBusinessDescription());
        resultChain.setAssumptions(externalChain.getContent().getAssumptions());
        resultChain.setOutOfScope(externalChain.getContent().getOutOfScope());

        resultChain.addLabels(createMissingChainLabels(externalChain.getContent().getLabels(), resultChain));

        Set<MaskedField> resultMaskedFields = resultChain.getMaskedFields();
        Set<MaskedField> externalMaskedFields = createMaskedFieldInternalEntities(externalChain.getContent().getMaskedFields(), resultChain);
        Set<MaskedField> mergedResultMaskedFields = resultMaskedFields
                .stream()
                .filter(resultMaskedField -> externalMaskedFields
                        .stream()
                        .anyMatch(externalMaskedField -> externalMaskedField.getName().equals(resultMaskedField.getName()))
                )
                .collect(Collectors.toSet());
        Set<MaskedField> mergedExternalMaskedFields = externalMaskedFields
                .stream()
                .filter(
                        externalMaskedField -> mergedResultMaskedFields
                                .stream()
                                .noneMatch(resultMaskedField -> resultMaskedField.getName().equals(externalMaskedField.getName()))
                )
                .collect(Collectors.toSet());
        resultChain.clearMaskedFields();
        resultChain.addMaskedFields(mergedResultMaskedFields);
        resultChain.addMaskedFields(mergedExternalMaskedFields);

        if (externalChain.getContent().getFolder() != null) {
            Folder newFolder = createFolderInternalEntity(externalChain.getContent().getFolder(), externalMapperEntity.getExistingFolder());
            resultChain.setParentFolder(newFolder);
        } else {
            resultChain.setParentFolder(null);
        }

        ChainElementsExternalMapperEntity elementsMapperEntity = ChainElementsExternalMapperEntity.builder()
                .chainElementExternalEntities(externalChain.getContent().getElements())
                .chainFilesDirectory(externalMapperEntity.getChainFilesDirectory())
                .build();
        resultChain.getElements().clear();
        resultChain.addElementsHierarchy(chainElementsMapper.toInternalEntity(elementsMapperEntity));
        specifyChainSwimlanes(externalChain, resultChain);

        if (externalMapperEntity.getActionBeforeDependencyMapping() != null) {
            resultChain = externalMapperEntity.getActionBeforeDependencyMapping().apply(resultChain);
        }

        enrichInternalChainWithDependencies(resultChain, currentDependencies, externalChain.getContent().getDependencies());
        return resultChain;
    }

    @Override
    public ChainExternalMapperEntity toExternalEntity(@NonNull Chain chain) {
        ChainElementsExternalMapperEntity elementsExternalMapperEntity = chainElementsMapper.toExternalEntity(chain.getElements());
        ChainExternalEntity chainExternalEntity = ChainExternalEntity.builder()
                .schema(chainSchemaUri)
                .id(chain.getId())
                .name(chain.getName())
                .content(ChainExternalContentEntity.builder()
                        .description(chain.getDescription())
                        .businessDescription(chain.getBusinessDescription())
                        .assumptions(chain.getAssumptions())
                        .outOfScope(chain.getOutOfScope())
                        .labels(chain.getLabels().stream().map(ChainLabel::getName).collect(Collectors.toList()))
                        .folder(createFolderExternalEntity(chain))
                        .modifiedWhen(chain.getModifiedWhen())
                        .maskedFields(createMaskedFieldExternalEntities(chain.getMaskedFields()))
                        .elements(elementsExternalMapperEntity.getChainElementExternalEntities())
                        .dependencies(extractExternalDependencies(chain))
                        .migrations(chainFileMigrationService.getActualMigrationVersions().stream()
                                .sorted()
                                .toList()
                                .toString())
                        .build())
                .build();

        return ChainExternalMapperEntity.builder()
                .chainExternalEntity(chainExternalEntity)
                .elementPropertyFiles(elementsExternalMapperEntity.getElementPropertyFiles())
                .build();
    }

    private void specifyChainSwimlanes(ChainExternalEntity externalChain, Chain resultChain) {
        resultChain.setDefaultSwimlane(null);
        resultChain.setReuseSwimlane(null);

        if (externalChain.getContent().getDefaultSwimlaneId() != null) {
            SwimlaneChainElement defaultSwimlane = resultChain.getElements().stream()
                    .filter(element -> externalChain.getContent().getDefaultSwimlaneId().equals(element.getId()))
                    .filter(element -> element instanceof SwimlaneChainElement)
                    .findFirst()
                    .map(element -> (SwimlaneChainElement) element)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Default swimlane " + externalChain.getContent().getDefaultSwimlaneId() + " not found"));
            resultChain.setDefaultSwimlane(defaultSwimlane);
            defaultSwimlane.setDefaultSwimlane(true);
        }

        if (externalChain.getContent().getReuseSwimlaneId() != null) {
            SwimlaneChainElement reuseSwimlane = resultChain.getElements().stream()
                    .filter(element -> externalChain.getContent().getReuseSwimlaneId().equals(element.getId()))
                    .filter(element -> element instanceof SwimlaneChainElement)
                    .findFirst()
                    .map(element -> (SwimlaneChainElement) element)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Reuse swimlane " + externalChain.getContent().getReuseSwimlaneId() + " not found"));
            resultChain.setReuseSwimlane(reuseSwimlane);
            reuseSwimlane.setReuseSwimlane(true);
        }
    }

    private void enrichInternalChainWithDependencies(Chain resultChain, Set<Dependency> currentDependencies, List<DependencyExternalEntity> dependencyExternalEntities) {
        Map<String, ChainElement> elements = extractAllElements(resultChain.getElements());

        Map<String, Dependency> existingDependencies = new HashMap<>();

        currentDependencies.forEach(currentDependency -> {
            String fromId = currentDependency.getElementFrom().getId();
            String toId = currentDependency.getElementTo().getId();
            boolean importedDependencyExists = dependencyExternalEntities.stream()
                    .anyMatch(dependency -> fromId.equals(dependency.getFrom()) && toId.equals(dependency.getTo()));
            if (importedDependencyExists) {
                existingDependencies.put(generateDependencyKey(fromId, toId), currentDependency);
            }
        });

        for (DependencyExternalEntity dependencyEntity : dependencyExternalEntities) {
            ChainElement elementFrom = elements.get(dependencyEntity.getFrom());
            ChainElement elementTo = elements.get(dependencyEntity.getTo());

            if (elementFrom == null || elementTo == null) {
                throw new IllegalArgumentException("Unable to create dependency. At least one element not found: " + dependencyEntity);
            }

            Dependency dependency = existingDependencies.getOrDefault(
                    generateDependencyKey(dependencyEntity.getFrom(), dependencyEntity.getTo()),
                    Dependency.of(elementFrom, elementTo)
            );

            elementFrom.getOutputDependencies().add(dependency);
            elementTo.getInputDependencies().add(dependency);
        }
    }

    private List<DependencyExternalEntity> extractExternalDependencies(Chain chain) {
        return chain.getElements().stream()
                .flatMap(element -> Stream.concat(element.getInputDependencies().stream(), element.getOutputDependencies().stream()))
                .filter(DistinctByKey.newInstance(Dependency::getId))
                .map(dependency -> new DependencyExternalEntity(dependency.getElementFrom().getId(), dependency.getElementTo().getId()))
                .collect(Collectors.toList());
    }

    private Folder createFolderInternalEntity(FolderExternalEntity folderExternalEntity, Folder existingRootFolder) {
        Folder resultFolder = null;
        FolderExternalEntity currentFolderExternalEntity = folderExternalEntity;
        while (currentFolderExternalEntity != null) {
            Folder newFolder;

            if (existingRootFolder != null) {
                newFolder = existingRootFolder;
                newFolder.setName(currentFolderExternalEntity.getName());
                newFolder.setDescription(currentFolderExternalEntity.getDescription());

                String subfolderName = Optional.ofNullable(currentFolderExternalEntity.getSubfolder())
                        .map(FolderExternalEntity::getName)
                        .orElse("");
                existingRootFolder = existingRootFolder.getFolderList().stream()
                        .filter(folder -> subfolderName.equals(folder.getName()))
                        .findFirst()
                        .orElse(null);
            } else {
                newFolder = Folder.builder()
                        .name(currentFolderExternalEntity.getName())
                        .description(currentFolderExternalEntity.getDescription())
                        .build();

                if (resultFolder != null) {
                    resultFolder.addChildFolder(newFolder);
                }
            }

            resultFolder = newFolder;
            currentFolderExternalEntity = currentFolderExternalEntity.getSubfolder();
        }

        return resultFolder;
    }

    private FolderExternalEntity createFolderExternalEntity(FoldableEntity folder) {
        FolderExternalEntity folderExternalEntity = null;
        while (folder.getParentFolder() != null) {
            folder = folder.getParentFolder();
            folderExternalEntity = FolderExternalEntity.builder()
                    .name(folder.getName())
                    .description(folder.getDescription())
                    .subfolder(folderExternalEntity)
                    .build();
        }
        return folderExternalEntity;
    }

    private Set<ChainLabel> createMissingChainLabels(List<String> labels, Chain resultChain) {
        if (CollectionUtils.isEmpty(labels)) {
            return Collections.emptySet();
        }

        return labels.stream()
                .filter(label -> !resultChain.getLabels().stream().map(ChainLabel::getName).toList().contains(label))
                .map(label -> ChainLabel.builder().name(label).chain(resultChain).build())
                .collect(Collectors.toSet());
    }

    private Set<MaskedField> createMaskedFieldInternalEntities(Set<MaskedFieldExternalEntity> maskedFieldExtEntities, Chain chain) {
        Set<MaskedField> maskedFields = new HashSet<>();
        for (MaskedFieldExternalEntity maskedFieldExtEntity : maskedFieldExtEntities) {
            maskedFields.add(MaskedField.builder()
                    .name(maskedFieldExtEntity.getName())
                    .chain(chain)
                    .build());
        }
        return maskedFields;
    }

    private Set<MaskedFieldExternalEntity> createMaskedFieldExternalEntities(Set<MaskedField> maskedFields) {
        return maskedFields.stream()
                .map(maskedField -> new MaskedFieldExternalEntity(maskedField.getId(), maskedField.getName()))
                .collect(Collectors.toSet());
    }

    private Map<String, ChainElement> extractAllElements(List<ChainElement> elements) {
        Map<String, ChainElement> result = new HashMap<>();
        for (ChainElement element : elements) {
            result.put(element.getId(), element);

            if (element instanceof ContainerChainElement containerElement) {
                result.putAll(containerElement.extractAllChildElements());
            }
        }
        return result;
    }

    private String generateDependencyKey(String fromId, String toId) {
        return fromId + "_" + toId;
    }
}
