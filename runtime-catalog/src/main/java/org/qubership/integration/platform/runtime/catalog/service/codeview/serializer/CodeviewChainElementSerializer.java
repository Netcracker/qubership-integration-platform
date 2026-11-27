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

package org.qubership.integration.platform.runtime.catalog.service.codeview.serializer;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.util.Map;
import java.util.TreeMap;


@Slf4j
public class CodeviewChainElementSerializer extends StdSerializer<ChainElement> {

    public CodeviewChainElementSerializer() {
        super(ChainElement.class);
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public void serialize(ChainElement element, JsonGenerator generator, SerializationContext serializer) {
        try {
            generator.writeStartObject();

            generator.writeStringProperty("id", element.getId());
            if (element.getName() != null) {
                generator.writeStringProperty("name", element.getName());
            }
            generator.writeStringProperty("description", element.getDescription());

            writeProperties(element, generator);

            generator.writeEndObject();
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Exception while serializing ChainElement {}, exception: ", element.getId(), e);
            throw e;
        }
    }

    private void writeProperties(ChainElement element, JsonGenerator generator) {
        Map<String, Object> properties = new TreeMap<>(element.getProperties());
        generator.writeName("properties");
        generator.writePOJO(properties);
    }

}
