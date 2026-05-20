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

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Object containing information about the differences between two chain elements")
public class ChainElementDifferenceDTO {

    @Schema(description = "Original chain element object")
    private ChainElementDTO leftElement;
    @Schema(description = "Modified chain element object")
    private ChainElementDTO rightElement;
    @Schema(description = "Field names, that exist only in original element")
    private Set<String> onlyOnLeft;
    @Schema(description = "Field names, that exist only in modified element")
    private Set<String> onlyOnRight;
    @Schema(description = "Field names, that exist in both elements with different values")
    private Set<String> differing;
}
