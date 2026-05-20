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

package org.qubership.integration.platform.engine.service.debugger.util.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Set;

import static org.qubership.integration.platform.engine.service.debugger.util.json.IdentitySetHelper.createIdentitySet;

public class JsonSerializationHelper {
    private static String serialize(Object value, JsonSerializer<Object> serializer) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        SimpleModule module = new SimpleModule("module", Version.unknownVersion());
        module.addSerializer(Object.class, serializer);
        objectMapper.registerModule(module);
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper.writeValueAsString(value);
    }

    public static String serializeJson(Object value) throws JsonProcessingException {
        Set<Object> selfReferencedObjects = createIdentitySet();
        String serializedValue = serialize(value, new CircularReferencesFinderSerializer(selfReferencedObjects::add));
        return selfReferencedObjects.isEmpty()
                ? serializedValue
                : serialize(value, new CircularReferencesAwareSerializer(selfReferencedObjects));
    }
}
