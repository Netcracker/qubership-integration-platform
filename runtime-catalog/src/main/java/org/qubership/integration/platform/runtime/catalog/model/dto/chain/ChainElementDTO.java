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

package org.qubership.integration.platform.runtime.catalog.model.dto.chain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Object containing information about chain/snapshot element")
public class ChainElementDTO {

    @Schema(description = "Original id of the element (for chain element - value from id field, for snapshot element - original id which element was copied from)")
    private String originalId;
    @Schema(description = "Parent element original id (container)")
    private String parentElementId;
    @Schema(description = "Inner element type")
    private String type;
    @Schema(description = "Chain element name")
    private String name;
    @Schema(description = "Chain element description")
    private String description;
    @Schema(description = "List of element child original ids (if current element is container element)")
    private List<String> childrenOriginalIds;
    @Schema(description = "Properties (data) of the element")
    private Map<String, Object> properties;
}
