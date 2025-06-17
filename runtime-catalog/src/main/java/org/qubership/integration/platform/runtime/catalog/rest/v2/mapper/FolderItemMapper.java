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
    Folder asFolder(CreateFolderRequest request);

    Folder asFolder(UpdateFolderRequest request);

    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(target = "itemType", constant = "FOLDER")
    FolderItem asFolderItem(Folder folder);
}
