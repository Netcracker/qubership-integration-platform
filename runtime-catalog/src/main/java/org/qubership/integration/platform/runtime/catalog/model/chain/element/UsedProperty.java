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

package org.qubership.integration.platform.runtime.catalog.model.chain.element;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@AllArgsConstructor
@Schema(description = "Exchange property")
public class UsedProperty {
    @Schema(description = "Property name")
    private String name;
    @Schema(description = "Property source")
    private UsedPropertySource source;
    @Schema(description = "Property type")
    private UsedPropertyType type;
    @JsonProperty("isArray")
    @Schema(description = "Whether property is an array")
    private boolean isArray;
    @Schema(description = "Map of attribute data types in case of complex object property")
    private Map<String, Object> attributeDataType;
    @Builder.Default
    @Schema(description = "Map of <property name + property source> and property element response object")
    private Map<String, UsedPropertyElement> relatedElements = new HashMap<>();
}
