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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class CircularReferencesAwareSerializer extends DelegatingSerializerBase {
    private final Set<Object> selfReferencedObjects;
    private final Map<Object, UUID> idMap;

    public CircularReferencesAwareSerializer(Set<Object> selfReferencedObjects) {
        super();
        this.selfReferencedObjects = selfReferencedObjects;
        this.idMap = new IdentityHashMap<>();
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (selfReferencedObjects.contains(value)) {
            if (idMap.containsKey(value)) {
                gen.writeString(idMap.get(value).toString());
            } else {
                UUID identifier = UUID.randomUUID();
                idMap.put(value, identifier);
                gen.writeRaw("{\"@json-id\":\"" + identifier.toString() + "\",\"reference\":");
                getSerializer(value).serialize(value, gen, serializers);
                gen.writeRaw("}");
            }
        } else {
            getSerializer(value).serialize(value, gen, serializers);
        }
    }
}
