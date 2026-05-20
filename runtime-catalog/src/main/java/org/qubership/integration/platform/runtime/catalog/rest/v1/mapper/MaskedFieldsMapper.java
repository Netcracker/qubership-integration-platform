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

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.masking.MaskedFieldDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.masking.MaskedFieldsResponse;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

@Mapper(componentModel = "spring", uses = { MapperUtils.class })
public interface MaskedFieldsMapper {
    @Mapping(source = "maskedFields", target = "fields")
    MaskedFieldsResponse asResponse(Chain entity);

    MaskedFieldDTO asDto(MaskedField entity);

    @Mapping(source = "chain", target = "chain")
    @Mapping(source = "dto.name", target = "name")
    @Mapping(source = "dto.createdBy", target = "createdBy")
    @Mapping(source = "dto.createdWhen", target = "createdWhen")
    @Mapping(source = "dto.modifiedBy", target = "modifiedBy")
    @Mapping(source = "dto.modifiedWhen", target = "modifiedWhen")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "description", ignore = true)
    MaskedField asEntity(Chain chain, MaskedFieldDTO dto);

    MaskedField asEntity(MaskedFieldDTO dto);

}
