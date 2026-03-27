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

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.ser.BeanSerializerFactory;
import com.fasterxml.jackson.databind.ser.DefaultSerializerProvider;

abstract class DelegatingSerializerBase extends JsonSerializer<Object> {
    private final SerializerProvider provider;

    public DelegatingSerializerBase() {
        SerializationConfig config = new ObjectMapper().getSerializationConfig()
                .without(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        this.provider = new DefaultSerializerProvider.Impl().createInstance(config, BeanSerializerFactory.instance);
    }

    protected JsonSerializer<Object> getSerializer(Object value) throws JsonMappingException {
        return provider.findTypedValueSerializer(value.getClass(), true, null);
    }
}
