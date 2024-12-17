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

package org.qubership.integration.platform.engine.camel.components.rabbitmq.serializers;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.rabbitmq.client.LongString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LongStringSerializer extends StdSerializer<LongString> {

    public LongStringSerializer(Class<LongString> t) {
        super(t);
    }

    @Override
    public void serialize(LongString value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        try {
            generator.writeString(new String(value.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Exception while serializing {} object", LongString.class.getName(), e);
            throw e;
        }
    }
}
