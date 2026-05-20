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

package org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.action;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum QIPCustomActionType {
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPGetUUID")
    GET_UUID("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPGetUUID"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentTime")
    GET_CURRENT_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentDate")
    GET_CURRENT_DATE("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentDate"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentDateTime")
    GET_CURRENT_DATE_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPCurrentDateTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPDefaultValue")
    GET_DEFAULT_VALUE("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPDefaultValue"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPFormatDateTime")
    FORMAT_DATE_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPFormatDateTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPDictionary")
    DICTIONARY("org.qubership.integration.platform.engine.util.AtlasMapUtils$QIPDictionary");

    private final String value;

    QIPCustomActionType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static QIPCustomActionType fromValue(String v) {
        for (QIPCustomActionType c : QIPCustomActionType.values()) {
            if (c.value.equalsIgnoreCase(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }
}
