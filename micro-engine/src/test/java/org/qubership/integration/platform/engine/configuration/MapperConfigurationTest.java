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

package org.qubership.integration.platform.engine.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MapperConfigurationTest {

    @Test
    void objectMapperShouldHaveExpectedSettings() throws Exception {
        MapperConfiguration cfg = new MapperConfiguration();
        ObjectMapper mapper = cfg.objectMapper();

        assertTrue(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT));
        assertFalse(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertEquals(JsonInclude.Include.NON_NULL, mapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion());

        String json = mapper.writeValueAsString(LocalDateTime.of(2025, 1, 1, 12, 30, 0));
        assertTrue(json.contains("2025-01-01T12:30"));
    }

    @Test
    void checkpointMapperShouldDisableFailOnEmptyBeans() throws Exception {
        MapperConfiguration cfg = new MapperConfiguration();
        ObjectMapper checkpoint = cfg.checkpointMapper();

        assertFalse(checkpoint.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));
        assertDoesNotThrow(() -> checkpoint.writeValueAsString(new Object()));
    }

    @Test
    void defaultObjectMapperShouldFailOnEmptyBeansByDefault() {
        MapperConfiguration cfg = new MapperConfiguration();
        ObjectMapper mapper = cfg.objectMapper();

        assertTrue(mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));
        assertThrows(InvalidDefinitionException.class, () -> mapper.writeValueAsString(new Object()));
    }
}
