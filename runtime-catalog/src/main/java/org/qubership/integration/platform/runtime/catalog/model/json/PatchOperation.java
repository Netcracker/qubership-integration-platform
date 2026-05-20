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

package org.qubership.integration.platform.runtime.catalog.model.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

public enum PatchOperation {
    ADD("add"),
    REPLACE("replace"),
    REMOVE("remove");

    private final String value;

    PatchOperation(String value) {
        this.value = value;
    }

    @Nullable
    @JsonCreator
    public static PatchOperation fromString(String value) {
        for (PatchOperation operation : values()) {
            if (StringUtils.equals(value, operation.getValue())) {
                return operation;
            }
        }

        return null;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
