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
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.function.Function;

@Schema(description = "Element parameter value type")
public enum PropertyValueType {

    @JsonProperty("string") STRING(val -> val),
    @JsonProperty("boolean") BOOLEAN(Boolean::valueOf),
    @JsonProperty("number") NUMBER(Long::valueOf),
    @JsonProperty("custom") CUSTOM(val -> val);

    @JsonIgnore
    private final Function<String, Object> converter;

    public Object convert(String value) {
        return converter.apply(value);
    }

    PropertyValueType(Function<String, Object> converter) {
        this.converter = converter;
    }
}
