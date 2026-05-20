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
import java.util.Set;
import java.util.function.Consumer;

import static org.qubership.integration.platform.engine.service.debugger.util.json.IdentitySetHelper.createIdentitySet;

class CircularReferencesFinderSerializer extends DelegatingSerializerBase {
    private final Set<Object> scope;
    private final Consumer<Object> consumer;

    public CircularReferencesFinderSerializer(Consumer<Object> consumer) {
        super();
        this.scope = createIdentitySet();
        this.consumer = consumer;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (scope.contains(value)) {
            consumer.accept(value);
        } else {
            scope.add(value);
            getSerializer(value).serialize(value, gen, serializers);
            scope.remove(value);
        }
    }
}
