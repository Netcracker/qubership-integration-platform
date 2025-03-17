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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.*;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.FolderService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ChainDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ElementDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.FolderSerializeEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils.toJsonPointer;

@Slf4j
@Component
@Deprecated(since = "2023.4")
public class ChainDeserializer extends StdDeserializer<ChainDeserializationResult> {
    private final ObjectMapper objectMapper;
    private final ChainRepository chainRepository;
    private final FolderService folderService;

    @Autowired
    public ChainDeserializer(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
                             ChainRepository chainRepository,
                             @Lazy FolderService folderService) {
        super((Class<?>) null);
        this.objectMapper = objectMapper;
        this.chainRepository = chainRepository;
        this.folderService = folderService;
    }

    @Override
    public ChainDeserializationResult deserialize(JsonParser parser, DeserializationContext context) {
        ChainDeserializationResult result = new ChainDeserializationResult();

        try {
            Chain resultChain;

            YAMLMapper mapper = (YAMLMapper) parser.getCodec();
            JsonNode node = mapper.readTree(parser);

            JsonNode idNode = node.get(ID);
            JsonNode nameNode = node.get(NAME);
            JsonNode descriptionNode = node.get(DESCRIPTION);
            JsonNode labelsNode = node.get(LABELS);
            JsonNode maskedFieldsNode = node.get(MASKED_FIELDS);
            JsonNode elementsNode = node.get(ELEMENTS);
            JsonNode dependenciesNode = node.get(DEPENDENCIES);
            JsonNode folderNode = node.get(FOLDER);
            JsonNode deploymentsNode = node.get(DEPLOYMENTS);
            JsonNode modifiedWhenNode = node.get(MODIFIED_WHEN);
            JsonNode deployActionNode = node.get(DEPLOY_ACTION);
            JsonNode businessDescription = node.get(BUSINESS_DESCRIPTION);
            JsonNode assumption = node.get(ASSUMPTIONS);
            JsonNode outOfScope = node.get(OUT_OF_SCOPE);

            Optional<Chain> chainOptional = chainRepository.findById(idNode.asText());
            if (chainOptional.isPresent()) {
                resultChain = chainOptional.get();
                resultChain.setModifiedWhen(new Timestamp(System.currentTimeMillis()));
                result.setStatus(ImportEntityStatus.UPDATED);
            } else {
                resultChain = Chain.builder()
                        .id(idNode.asText())
                        .createdWhen(new Timestamp(System.currentTimeMillis()))
                        .modifiedWhen(new Timestamp(modifiedWhenNode == null ?
                                System.currentTimeMillis() : modifiedWhenNode.asLong()))
                        .build();
                result.setStatus(ImportEntityStatus.CREATED);
            }

            resultChain.setName(nameNode != null ? nameNode.asText() : null);
            resultChain.setDescription(descriptionNode != null ? descriptionNode.asText() : null);
            resultChain.setBusinessDescription(businessDescription != null ? businessDescription.asText() : null);
            resultChain.setAssumptions(assumption != null ? assumption.asText() : null);
            resultChain.setOutOfScope(outOfScope != null ? outOfScope.asText() : null);

            result.setDefaultSwimlaneId(node.at(toJsonPointer(DEFAULT_SWIMLANE_ID)).asText(null));
            result.setReuseSwimlaneId(node.at(toJsonPointer(REUSE_SWIMLANE_ID)).asText(null));
            restoreFolders(folderNode, resultChain);
            restoreLabels(labelsNode, resultChain);
            restoreMaskedFields(maskedFieldsNode, resultChain);
            restoreElements(elementsNode, result, resultChain, mapper);
            restoreDependencies(dependenciesNode, resultChain);

            result.setChain(resultChain);
            result.setDeployAction(deployActionNode != null ? ChainCommitRequestAction.valueOf(deployActionNode.asText()) : null);
            restoreDeployments(deploymentsNode, result);
        } catch (Exception e) {
            log.error("Exception while chain deserialization: ", e);
            result.setStatus(ImportEntityStatus.ERROR);
            result.setErrorMessage("Exception while chain deserialization: " + e.getMessage());
        }
        return result;
    }

    private void restoreDeployments(JsonNode deploymentsNode, ChainDeserializationResult deserializationResult) throws JsonProcessingException {
        if (deploymentsNode == null) {
            return;
        }
        DeploymentExternalEntity[] deploymentsSerialized = objectMapper.treeToValue(deploymentsNode, DeploymentExternalEntity[].class);
        if (deploymentsSerialized != null) {
            deserializationResult.setDeployments(Arrays.asList(deploymentsSerialized));
        }
    }

    private void restoreFolders(JsonNode foldersNode, Chain chain) throws JsonProcessingException {
        if (foldersNode != null) {
            FolderSerializeEntity folderSerializeEntity = objectMapper.treeToValue(foldersNode, FolderSerializeEntity.class);
            Folder folder = restoreSubFolders(folderSerializeEntity, null);

            while (folderSerializeEntity.getSubfolder() != null) {
                folderSerializeEntity = folderSerializeEntity.getSubfolder();
                folder = restoreSubFolders(folderSerializeEntity, folder);
            }
            chain.setParentFolder(folder);
        }
    }

    private Folder restoreSubFolders(FolderSerializeEntity folderSerializeEntity, Folder parentFolder) {
        Folder folder = folderService.findFirstByName(folderSerializeEntity.getName(), parentFolder);
        if (folder == null) {
            folder = Folder.builder()
                    .name(folderSerializeEntity.getName())
                    .description(folderSerializeEntity.getDescription())
                    .parentFolder(folder)
                    .build();
        }
        return folder;
    }

    private void restoreMaskedFields(JsonNode maskedFieldsNode, Chain chain) {
        deleteMaskedFields(chain);
        if (maskedFieldsNode != null) {
            Set<String> fieldNames = new HashSet<>();
            for (final JsonNode maskedFieldNode : maskedFieldsNode) {
                fieldNames.add(maskedFieldNode.get(NAME).asText());
            }
            for (String fieldName : fieldNames) {
                chain.getMaskedFields().add(MaskedField.builder()
                        .name(fieldName)
                        .chain(chain)
                        .build()
                );
            }
        }
    }

    private void deleteMaskedFields(Chain chain) {
        Set<MaskedField> maskedFields = chain.getMaskedFields();
        maskedFields.clear();
    }

    private void restoreElements(JsonNode elementsNode, ChainDeserializationResult chainDeserializationResult,
                                 Chain resultChain, YAMLMapper mapper) throws JsonProcessingException {
        Map<String, SwimlaneChainElement> swimlanes = new HashMap<>();
        Map<String, ChainElement> newElementsMap = new HashMap<>();
        Map<String, String> propertyFilenames = new HashMap<>();
        if (elementsNode != null) {
            JsonPointer elementTypePointer = toJsonPointer(ELEMENT_TYPE);
            List<JsonNode> elementNodeList = StreamSupport.stream(elementsNode.spliterator(), false)
                    .peek(elementNode -> {
                        if (ElementService.SWIMLANE_TYPE_NAME.equals(elementNode.at(elementTypePointer).asText())) {
                            ElementDeserializationResult swimlaneDeserializationResult;
                            try {
                                swimlaneDeserializationResult = mapper
                                        .readValue(elementNode.toString(), ElementDeserializationResult.class);
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException("Exception while swimlane deserialization", e);
                            }
                            if (!(swimlaneDeserializationResult.getElement() instanceof SwimlaneChainElement swimlane)) {
                                throw new RuntimeException("Swimlane converted to invalid type");
                            }
                            swimlanes.put(swimlane.getId(), swimlane);
                        }
                    })
                    .filter(elementNode -> !ElementService.SWIMLANE_TYPE_NAME.equals(elementNode.at(elementTypePointer).asText()))
                    .toList();
            resultChain.setDefaultSwimlane(swimlanes.get(chainDeserializationResult.getDefaultSwimlaneId()));
            resultChain.setReuseSwimlane(swimlanes.get(chainDeserializationResult.getReuseSwimlaneId()));
            for (final JsonNode elementNode : elementNodeList) {
                ElementDeserializationResult elementDeserializeEntity =
                        mapper.readValue(elementNode.toString(), ElementDeserializationResult.class);
                ChainElement element = elementDeserializeEntity.getElement();
                Optional.ofNullable(swimlanes.get(elementDeserializeEntity.getSwimlaneIdByElementId(element.getId())))
                        .ifPresent(swimlane -> swimlane.addElement(element));
                newElementsMap.put(element.getId(), element);
                if (!CollectionUtils.isEmpty(elementDeserializeEntity.getElementPropertiesFilenames())) {
                    propertyFilenames.putAll(elementDeserializeEntity.getElementPropertiesFilenames());
                }
                if (element instanceof ContainerChainElement container) {
                    Map<String, ChainElement> allChildren = container.extractAllChildElements().entrySet().stream()
                            .peek(entry -> Optional.ofNullable(swimlanes
                                            .get(elementDeserializeEntity.getSwimlaneIdByElementId(entry.getKey())))
                                    .ifPresent(swimlane -> swimlane.addElement(entry.getValue())))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    newElementsMap.putAll(allChildren);
                }
            }
            newElementsMap.putAll(swimlanes);

            List<ChainElement> elements = resultChain.getElements();
            elements.removeIf(element -> newElementsMap.containsKey(element.getId()));
            deleteExcessElements(resultChain, new ArrayList<>(elements));

            resultChain.addElements(newElementsMap.values());
        }

        chainDeserializationResult.setPropertiesFileNames(propertyFilenames);
    }

    private void deleteExcessElements(Chain chain, Collection<ChainElement> elementsToDelete) {
        Map<String, ChainElement> elementsToDeleteMap = elementsToDelete.stream()
                .collect(Collectors.toMap(ChainElement::getId, Function.identity()));

        for (ChainElement element : elementsToDelete) {
            ContainerChainElement parent = element.getParent();
            if (parent != null && !elementsToDeleteMap.containsKey(parent.getId())) {
                parent.removeChildElement(element);
            }
        }
        chain.removeElements(elementsToDelete);
    }

    private void restoreDependencies(JsonNode dependenciesNode, Chain chain) {
        deleteDependencies(chain);
        if (dependenciesNode != null) {
            for (final JsonNode depNode : dependenciesNode) {
                JsonNode fromId = depNode.get(FROM);
                JsonNode toId = depNode.get(TO);
                ChainElement from = chain.getElements().stream().filter(el -> el.getId().equals(fromId.asText()))
                        .findFirst().orElseThrow();
                ChainElement to = chain.getElements().stream().filter(el -> el.getId().equals(toId.asText()))
                        .findFirst().orElseThrow();
                Dependency dep = Dependency.of(from, to);
                from.addOutputDependency(dep);
                to.addInputDependency(dep);
            }
        }
    }

    private void deleteDependencies(Chain chain) {
        List<ChainElement> elements = chain.getElements();
        Set<Dependency> dependencies = new HashSet<>();
        if (elements != null) {
            elements.forEach(el -> {
                dependencies.addAll(el.getInputDependencies());
                dependencies.addAll(el.getOutputDependencies());
                el.getInputDependencies().clear();
                el.getOutputDependencies().clear();
            });
        }
    }

    private void restoreLabels(JsonNode labelsNode, Chain chain) {
        if (labelsNode == null || !labelsNode.isArray()) {
            return;
        }

        StreamSupport.stream(labelsNode.spliterator(), false)
                .map(JsonNode::asText)
                .distinct()
                .forEach(label -> chain.addLabel(ChainLabel.builder().name(label).chain(chain).build()));
    }
}
