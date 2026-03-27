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

package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasSession;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static java.util.Objects.isNull;

public class ValueGeneratorFactory {
    Function<AtlasSession, String> getValueGenerator(String name, List<String> parameters) throws Exception {
        if (isNull(name)) {
            throw new Exception("Value generator name is null");
        }
        return switch (name) {
            case "generateUUID" -> session -> UUID.randomUUID().toString();
            case "currentDate", "currentTime", "currentDateTime" -> TimestampGenerator.fromParameterList(parameters);
            default -> throw new Exception("Unsupported value generator: " + name);
        };
    }
}
