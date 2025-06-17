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

import java.util.Map;

@Schema(description = "Exchange property type")
public enum UsedPropertyType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    UNKNOWN_TYPE;

    private static final Map<String, UsedPropertyType> STRING_MAPPING = Map.of(
            "string", STRING,
            "number", NUMBER,
            "boolean", BOOLEAN,
            "object", OBJECT
    );

    public static UsedPropertyType fromString(String type) {
        return type == null ? UsedPropertyType.UNKNOWN_TYPE : STRING_MAPPING.getOrDefault(type, UsedPropertyType.UNKNOWN_TYPE);
    }
}
