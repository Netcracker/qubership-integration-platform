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

package org.qubership.integration.platform.runtime.catalog.testutils.mapper;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.FoldableEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.FolderImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.MaskedFieldImportDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@TestComponent
public class ChainMapper implements ImportDTOMapper<Chain, ChainImportDTO> {

    private final ChainElementsMapper chainElementsMapper;

    @Autowired
    public ChainMapper(ChainElementsMapper chainElementsMapper) {
        this.chainElementsMapper = chainElementsMapper;
    }

    @Override
    public Chain toEntity(ChainImportDTO chainDTO) {
        Chain chain = Chain.builder()
                .id(chainDTO.getId())
                .name(chainDTO.getName())
                .description(chainDTO.getDescription())
                .modifiedWhen(chainDTO.getModifiedWhen())
                .businessDescription(chainDTO.getBusinessDescription())
                .assumptions(chainDTO.getAssumptions())
                .outOfScope(chainDTO.getOutOfScope())
                .build();
        List<ChainElement> elements = chainElementsMapper
                .toEntity(new ElementsDTO(chainDTO.getElements(), chainDTO.getDependencies()));
        if (chainDTO.getFolder() != null) {
            Folder folder = createFolder(chainDTO.getFolder());
            folder.addChildChain(chain);
        }
        chain.addElements(elements);
        chain.setMaskedFields(createMaskedFields(chainDTO.getMaskedFields(), chain));
        if (chainDTO.getDefaultSwimlaneId() != null) {
            SwimlaneChainElement defaultSwimlane = elements.stream()
                    .filter(element -> chainDTO.getDefaultSwimlaneId().equals(element.getId()))
                    .filter(element -> element instanceof SwimlaneChainElement)
                    .findFirst()
                    .map(element -> (SwimlaneChainElement) element)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Default swimlane " + chainDTO.getDefaultSwimlaneId() + " not found"));
            chain.setDefaultSwimlane(defaultSwimlane);
        }
        if (chainDTO.getReuseSwimlaneId() != null) {
            SwimlaneChainElement reuseSwimlane = elements.stream()
                    .filter(element -> chainDTO.getReuseSwimlaneId().equals(element.getId()))
                    .filter(element -> element instanceof SwimlaneChainElement)
                    .findFirst()
                    .map(element -> (SwimlaneChainElement) element)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Reuse swimlane " + chainDTO.getReuseSwimlaneId() + " not found"));
            chain.setReuseSwimlane(reuseSwimlane);
        }
        return chain;
    }

    @Override
    public ChainImportDTO toDto(Chain chain) {
        ElementsDTO elementDTOS = chainElementsMapper.toDto(chain.getElements());
        return ChainImportDTO.builder()
                .id(chain.getId())
                .name(chain.getName())
                .description(chain.getDescription())
                .folder(createFolderDto(chain))
                .modifiedWhen(chain.getModifiedWhen())
                .elements(elementDTOS.getElementImportDTOS())
                .dependencies(elementDTOS.getDependencyImportDTOS())
                .maskedFields(createMaskedFieldDtos(chain.getMaskedFields()))
                .defaultSwimlaneId(Optional.ofNullable(chain.getDefaultSwimlane()).map(ChainElement::getId).orElse(null))
                .reuseSwimlaneId(Optional.ofNullable(chain.getReuseSwimlane()).map(ChainElement::getId).orElse(null))
                .build();
    }

    private Folder createFolder(FolderImportDTO folderDTO) {
        Folder folder = Folder.builder()
                .name(folderDTO.getName())
                .description(folderDTO.getDescription())
                .build();
        if (folderDTO.getSubfolder() != null) {
            folder.addChildFolder(createFolder(folderDTO.getSubfolder()));
        }
        return folder;
    }

    private FolderImportDTO createFolderDto(FoldableEntity folder) {
        FolderImportDTO folderDTO = null;
        while (folder.getParentFolder() != null) {
            folder = folder.getParentFolder();
            folderDTO = FolderImportDTO.builder()
                    .name(folder.getName())
                    .description(folder.getDescription())
                    .subfolder(folderDTO)
                    .build();
        }
        return folderDTO;
    }

    private Set<MaskedField> createMaskedFields(Set<MaskedFieldImportDTO> maskedFieldDTOS, Chain chain) {
        Set<MaskedField> maskedFields = new HashSet<>();
        for (MaskedFieldImportDTO maskedFieldDTO : maskedFieldDTOS) {
            maskedFields.add(MaskedField.builder()
                    .id(maskedFieldDTO.getId())
                    .name(maskedFieldDTO.getName())
                    .chain(chain)
                    .build());
        }
        return maskedFields;
    }

    private Set<MaskedFieldImportDTO> createMaskedFieldDtos(Set<MaskedField> maskedFields) {
        return maskedFields.stream()
                .map(maskedField -> new MaskedFieldImportDTO(maskedField.getId(), maskedField.getName()))
                .collect(Collectors.toSet());
    }
}
