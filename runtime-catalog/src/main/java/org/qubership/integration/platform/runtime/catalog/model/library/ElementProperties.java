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

package org.qubership.integration.platform.runtime.catalog.model.library;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@Schema(description = "Element descriptor properties")
public class ElementProperties {
    @Schema(description = "List of common properties")
    private List<ElementProperty> common = new ArrayList<>();
    @Schema(description = "List of advanced properties")
    private List<ElementProperty> advanced = new ArrayList<>();
    @Schema(description = "List of hidden properties")
    private List<ElementProperty> hidden = new ArrayList<>();
    @Schema(description = "List of properties for async elements")
    private List<ElementProperty> async = new ArrayList<>();

    @JsonIgnore
    public List<ElementProperty> getQueryProperties() {
        return Stream.of(common.stream(), advanced.stream(), hidden.stream(), async.stream())
                .flatMap(i -> i)
                .filter(ElementProperty::isQuery)
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ElementProperty> getAll() {
        return Stream.of(common.stream(), advanced.stream(), hidden.stream(), async.stream())
                .flatMap(i -> i)
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ElementProperty> getReferenceProperties() {
        return Stream.of(common.stream(), advanced.stream(), hidden.stream(), async.stream())
                .flatMap(i -> i)
                .filter(ElementProperty::isReference)
                .collect(Collectors.toList());
    }
}
