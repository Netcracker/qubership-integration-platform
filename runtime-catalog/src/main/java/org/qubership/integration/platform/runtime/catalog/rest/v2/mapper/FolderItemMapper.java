package org.qubership.integration.platform.runtime.catalog.rest.v2.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.CreateFolderRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.FolderItem;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.UpdateFolderRequest;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = {
                MapperUtils.class,
                UserMapper.class
        }
)
public interface FolderItemMapper {
    @Mapping(target = "id", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "parentFolder", ignore = true)
    @Mapping(target = "folderList", ignore = true)
    @Mapping(target = "chainList", ignore = true)
    Folder asFolder(CreateFolderRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "parentFolder", ignore = true)
    @Mapping(target = "folderList", ignore = true)
    @Mapping(target = "chainList", ignore = true)
    Folder asFolder(UpdateFolderRequest request);

    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(target = "itemType", constant = "FOLDER")
    FolderItem asFolderItem(Folder folder);
}
