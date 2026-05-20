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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@Schema(description = "Custom validation logic")
public class PropertyValidation {
    @Schema(description = "List of property names for anyOf scenario")
    private List<String> anyOf = new ArrayList<>();
    @Schema(description = "List of property names for allOf scenario")
    private List<String> allOf = new ArrayList<>();
    @Schema(description = "List of conditions ")
    private List<PropertyCondition> conditions = new ArrayList<>();

    public boolean arePropertiesValid(Map<String, Object> properties) {
        boolean valid = true;
        if (!anyOf.isEmpty()) {
            valid = anyOf.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(propertyName -> ObjectUtils.isNotEmpty(properties.get(propertyName)));
        }
        if (!allOf.isEmpty()) {
            valid = allOf.stream()
                    .filter(Objects::nonNull)
                    .allMatch(propertyName -> ObjectUtils.isNotEmpty(properties.get(propertyName)));
        }
        if (!conditions.isEmpty()) {
            valid = conditions.stream()
                    .filter(Objects::nonNull)
                    .allMatch(condition -> condition.evaluate(properties));
        }
        return valid;
    }


    @Data
    @Schema(description = "Property validation condition")
    static class PropertyCondition {
        @Schema(description = "Property name")
        private String property;
        @Schema(description = "Value equals to")
        private String equalTo;
        @Schema(description = "List of mandatory properties if value is equal to \"equalTo\" field")
        private List<String> mandatoryProperties = new ArrayList<>();

        boolean evaluate(Map<String, Object> properties) {
            if (!mandatoryProperties.isEmpty()
                    && StringUtils.equalsIgnoreCase(Objects.toString(properties.get(property), null), equalTo)) {
                return mandatoryProperties.stream()
                        .allMatch(propertyName -> ObjectUtils.isNotEmpty(properties.get(propertyName)));
            }
            return true;
        }
    }
}
