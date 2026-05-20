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


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Builder
@AllArgsConstructor
@Schema(description = "Exchange property with element using it")
public class UsedPropertyElement {
    @Schema(description = "Element id")
    private String id;
    @Schema(description = "Element name")
    private String name;
    @Schema(description = "Inner element type")
    private String type;

    @Builder.Default
    @Schema(description = "Operations for a property used in this element")
    private Set<UsedPropertyElementOperation> operations = new HashSet<>();

    public void merge(UsedPropertyElement propertyElement) {
        if (propertyElement != null && propertyElement.getOperations() != null) {
            this.operations.addAll(propertyElement.getOperations());
        }
    }
}
