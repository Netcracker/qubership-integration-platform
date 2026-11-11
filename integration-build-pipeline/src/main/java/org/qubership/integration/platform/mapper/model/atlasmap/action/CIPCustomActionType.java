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

package org.qubership.integration.platform.mapper.model.atlasmap.action;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CIPCustomActionType {
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPGetUUID")
    GET_UUID("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPGetUUID"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentTime")
    GET_CURRENT_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentDate")
    GET_CURRENT_DATE("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentDate"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentDateTime")
    GET_CURRENT_DATE_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPCurrentDateTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPDefaultValue")
    GET_DEFAULT_VALUE("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPDefaultValue"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPFormatDateTime")
    FORMAT_DATE_TIME("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPFormatDateTime"),
    @JsonProperty("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPDictionary")
    DICTIONARY("org.qubership.integration.platform.engine.util.AtlasMapUtils$CIPDictionary");

    private final String value;

    CIPCustomActionType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CIPCustomActionType fromValue(String v) {
        for (CIPCustomActionType c : CIPCustomActionType.values()) {
            if (c.value.equalsIgnoreCase(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }
}
