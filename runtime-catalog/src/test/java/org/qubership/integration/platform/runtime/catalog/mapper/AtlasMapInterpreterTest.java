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

package org.qubership.integration.platform.runtime.catalog.mapper;

import io.atlasmap.api.AtlasException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.AtlasMapInterpreter;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class AtlasMapInterpreterTest {
    private static final String DEFAULT_VALUE_CONFIG = "mapper/mapping/config/13_default_value.yml";

    private MappingInterpreter interpreter;


    @BeforeEach
    void setUp() {
        interpreter = new AtlasMapInterpreter(MapperTestUtils.OBJECT_MAPPER);
    }


    @Test
    void interpretationDefaultValue() throws IOException, AtlasException {
        File configurationFile = MapperTestUtils.getConfigurationFile(DEFAULT_VALUE_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        assertAll(
                () -> assertNotNull(atlasMapInterpretation)
        );
    }
}
