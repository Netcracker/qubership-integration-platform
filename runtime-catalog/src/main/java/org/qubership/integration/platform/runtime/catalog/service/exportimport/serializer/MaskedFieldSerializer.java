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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.MaskedField;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

@Slf4j
@JsonComponent
@Deprecated(since = "2023.4")
public class MaskedFieldSerializer extends StdSerializer<MaskedField> {
    public MaskedFieldSerializer() {
        this(null);
    }

    public MaskedFieldSerializer(Class<MaskedField> t) {
        super(t);
    }

    @Override
    public void serialize(MaskedField maskedField, JsonGenerator generator, SerializerProvider serializer) throws IOException {
        try {
            generator.writeStartObject();
            if (maskedField.getName() != null) {
                generator.writeStringField("name", maskedField.getName());
            }
            generator.writeEndObject();
        } catch (IOException e) {
            log.warn("Exception while serializing MaskedField {}, exception: ", maskedField.getId(), e);
            throw e;
        }
    }
}
