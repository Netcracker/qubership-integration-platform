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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Response object for a single element of the chain")
public class ElementResponse extends BaseResponse {
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Inner element type")
    private String type;
    @Schema(description = "Parent element id (container)")
    private String parentElementId;
    @Schema(description = "Original id of the element (in case this element is from snapshot - it stores original id which element was copied from)")
    private String originalId;
    @Schema(description = "Properties (data) of the element")
    private Map<String, Object> properties;
    @Schema(description = "List of element children (if current element is container element)")
    private List<ElementResponse> children;
    @Schema(description = "Swimlane id which this element belongs to (if any)")
    private String swimlaneId;
    @Schema(description = "Whether element is configured correctly")
    private boolean mandatoryChecksPassed;

}
