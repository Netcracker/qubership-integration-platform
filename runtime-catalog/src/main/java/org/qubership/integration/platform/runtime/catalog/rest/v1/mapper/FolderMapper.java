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

package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import lombok.NoArgsConstructor;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.*;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@Mapper(componentModel = "spring", uses = {
        ChainMapper.class,
        MapperUtils.class,
        UserMapper.class,
        ChainLabelsMapper.class
})
@NoArgsConstructor
public abstract class FolderMapper {

    @Autowired
    private ChainMapper chainMapper;

    public abstract Folder asEntity(FolderItemRequest request);

    @Mapping(source = "parentFolder", target = "parentId")
    public FolderResponse asResponse(Folder folder) {
        var folderItem = new FolderResponse();
        merge(folderItem, folder);
        buildItemList(folderItem, folder);
        return folderItem;
    }

    public List<SearchFilterItemResponse> asSearchItemResponse(Collection<Folder> folders) {
        return folders.stream().map(this::asFolderSearchResponse).toList();
    }

    @Mapping(source = "parentFolder.id", target = "parentId")
    public abstract SearchFilterItemResponse asSearchItemResponse(Folder entity);

    public SearchFilterItemResponse asFolderSearchResponse(Folder entity) {
        SearchFilterItemResponse searchItemResponse = asSearchItemResponse(entity);
        boolean containsFolders = entity.getFolderList() != null;
        boolean containsChains = entity.getChainList() != null;
        if (containsFolders || containsChains) {
            searchItemResponse.setItems(
                    Stream.concat(
                                    !containsFolders ? Stream.empty() : entity.getFolderList().stream(),
                                    !containsChains ? Stream.empty() : entity.getChainList().stream())
                            .map(foldableEntity -> foldableEntity instanceof Chain
                                    ? chainMapper.asFolderItemResponse((Chain) foldableEntity)
                                    : this.asFolderItemResponse((Folder) foldableEntity))
                            .toList());
        }
        return searchItemResponse;
    }

    @IterableMapping(elementTargetType = FolderItemResponse.class)
    public abstract List<FolderItemResponse> asFolderItemResponse(Collection<Folder> folder);

    @Mapping(source = "parentFolder.id", target = "parentId")
    public abstract FolderItemResponse asFolderItemResponse(Folder entity);

    @Mapping(source = "parentFolder.id", target = "parentId")
    public abstract void merge(@MappingTarget FolderResponse dto, Folder entity);

    private void buildItemList(@MappingTarget FolderResponse responseDTO, Folder folder) {
        var items = new ArrayList<FolderItemResponse>();

        var chains = folder.getChainList();
        var chainFolderItems = chainMapper.asFolderItemResponse(chains);
        if (chainFolderItems != null) {
            chainFolderItems.forEach(element -> element.setItemType(ItemType.CHAIN));
            items.addAll(chainFolderItems);
        }

        var folders = folder.getFolderList();
        var folderItems = asFolderItemResponse(folders);
        if (folderItems != null) {
            folderItems.forEach(element -> element.setItemType(ItemType.FOLDER));
            items.addAll(folderItems);
        }

        responseDTO.setItems(items);
    }
}
