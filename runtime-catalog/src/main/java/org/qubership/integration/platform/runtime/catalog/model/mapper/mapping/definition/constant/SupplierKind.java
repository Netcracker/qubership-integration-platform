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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.SupplierKind.Constants.GENERATED_VALUE;
import static org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.SupplierKind.Constants.GIVEN_VALUE;

@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public enum SupplierKind {
    @JsonProperty(GIVEN_VALUE)
    GIVEN(GIVEN_VALUE),
    @JsonProperty(GENERATED_VALUE)
    GENERATED(GENERATED_VALUE);

    private final String value;

    SupplierKind(String value) {
        this.value = value;
    }

    public static SupplierKind fromValue(String v) {
        for (SupplierKind c : SupplierKind.values()) {
            if (c.value.equalsIgnoreCase(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }

    public static class Constants {
        public static final String GIVEN_VALUE = "given";
        public static final String GENERATED_VALUE = "generated";
    }

}
