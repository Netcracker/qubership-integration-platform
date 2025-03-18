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

package org.qubership.integration.platform.engine.opensearch.ism.model.actions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.stream.Stream;

public enum Backoff {
    EXPONENTIAL("exponential"),
    CONSTANT("constant"),
    LINEAR("linear");

    private final String type;

    Backoff(String type) {
        this.type = type;
    }

    @JsonCreator
    public static Backoff fromString(String type) {
        return Stream.of(Backoff.values()).filter(v -> v.getType().equals(type)).findAny().orElse(null);
    }

    @JsonValue
    public String getType() {
        return type;
    }
}