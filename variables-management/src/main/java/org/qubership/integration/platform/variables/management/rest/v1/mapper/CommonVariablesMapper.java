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

package org.qubership.integration.platform.variables.management.rest.v1.mapper;

import lombok.NoArgsConstructor;
import org.mapstruct.Mapper;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariableDTO;
import org.qubership.integration.platform.variables.management.util.MapperUtils;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", uses = {
        MapperUtils.class,
        UserMapper.class
})
@NoArgsConstructor
public abstract class CommonVariablesMapper {
    public List<String> importAsNames(List<ImportVariableDTO> variables) {
        return CollectionUtils.isEmpty(variables)
                ? Collections.emptyList()
                : variables.stream().map(this::asName).collect(Collectors.toList());
    }

    public String asName(ImportVariableDTO variable) {
        return variable == null ? "" : variable.getName();
    }
}
