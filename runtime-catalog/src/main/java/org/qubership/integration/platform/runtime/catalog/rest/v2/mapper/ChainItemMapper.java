package org.qubership.integration.platform.runtime.catalog.rest.v2.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainLabelsMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ChainItem;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = {
                MapperUtils.class,
                UserMapper.class,
                StringTrimmer.class,
                ChainLabelsMapper.class
        })
public interface ChainItemMapper {
    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(target = "itemType", constant = "CHAIN")
    ChainItem asChainItem(Chain chain);
}
