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

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainElementImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.DependencyImportDTO;

import java.util.Collections;
import java.util.List;

public class ElementsDTO {

    private final List<ChainElementImportDTO> elementImportDTOS;
    private final List<DependencyImportDTO> dependencyImportDTOS;

    public ElementsDTO(
            @JsonProperty("elements") List<ChainElementImportDTO> elementImportDTOS,
            @JsonProperty("dependencies") List<DependencyImportDTO> dependencyImportDTOS
    ) {
        this.elementImportDTOS = elementImportDTOS != null ? elementImportDTOS : Collections.emptyList();
        this.dependencyImportDTOS = dependencyImportDTOS != null ? dependencyImportDTOS : Collections.emptyList();
    }

    public List<ChainElementImportDTO> getElementImportDTOS() {
        return elementImportDTOS;
    }

    public List<DependencyImportDTO> getDependencyImportDTOS() {
        return dependencyImportDTOS;
    }
}
