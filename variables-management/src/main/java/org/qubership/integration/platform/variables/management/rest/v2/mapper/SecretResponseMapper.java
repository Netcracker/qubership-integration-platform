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

package org.qubership.integration.platform.variables.management.rest.v2.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecretResponse;
import org.qubership.integration.platform.variables.management.service.SecretService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class SecretResponseMapper {

    @Autowired
    private SecretService secretService;

    @Mapping(target = "secretName", source = "key")
    @Mapping(target = "variablesNames", source = "value")
    @Mapping(target = "defaultSecret", expression = "java(isDefaultSecret(secretVariable.getKey()))")
    public abstract SecretResponse asResponse(Map.Entry<String, Set<String>> secretVariable);

    public List<SecretResponse> asResponse(Map<String, Set<String>> secrets) {
        return secrets.entrySet().stream()
                .map(this::asResponse)
                .collect(Collectors.toList());
    }

    public boolean isDefaultSecret(String secretName) {
        return secretService.isDefaultSecret(secretName);
    }
}
